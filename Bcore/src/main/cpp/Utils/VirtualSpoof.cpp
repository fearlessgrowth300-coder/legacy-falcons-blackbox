#include <sys/system_properties.h>
#include <cstring>
#include <string>
#include <map>
#include <mutex>
#include "./xdl.h"
#include <android/log.h>
#include <dlfcn.h>
#include "Dobby/dobby.h"


#define LOG_TAG "VirtualSpoof"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Per-clone spoof map. Populated at runtime (per guest process, keyed by userId)
// via NativeCore.spoofDevice() -> vs_set(). Empty by default, so the HOST process
// and any un-configured process read the REAL device values (no spoofing, no crash).
static std::mutex g_lock;
static std::map<std::string, std::string> g_spoof;

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;

// Modern property read path (Android 8+ / API 26+). android.os.SystemProperties.get()
// and the Build.* static initializers go through __system_property_read_callback, NOT
// the legacy __system_property_get — so we must hook this one too or native reads leak.
typedef void (*prop_read_cb_t)(void *cookie, const char *name, const char *value, uint32_t serial);
typedef void (*sys_prop_read_callback_t)(const prop_info *pi, prop_read_cb_t cb, void *cookie);
static sys_prop_read_callback_t orig_read_callback = nullptr;

static bool g_installed = false;

// Graphics / SoC selection keys. The EGL, Vulkan and gralloc loaders read these to
// pick a hardware-specific driver. Spoofing them to a mismatched SoC (e.g. telling an
// Exynos device it is "qcom"/"adreno") makes the GPU driver load the wrong vendor blob
// -> EGL_BAD_ACCESS / SIGABRT on RenderThread. The physical GPU can't be changed anyway
// (GL_RENDERER always reveals the real chip), so always pass these through to the real
// value. Without this the host app itself crashes on non-Qualcomm phones.
static bool is_graphics_key(const char *k) {
    if (!k) return false;
    static const char *prefixes[] = {
        "ro.hardware",          // ro.hardware, ro.hardware.egl/.vulkan/.gralloc/.hwcomposer
        "ro.board.platform",
        "ro.soc.",
        "ro.gfx.",
        "ro.boot.hardware",
        "ro.chipname",
        "ro.arch",
        "debug.egl",
        "vendor.hwc.",
    };
    for (const char *pre : prefixes) {
        if (strncmp(k, pre, strlen(pre)) == 0) return true;
    }
    return false;
}


int my_system_property_get(const char *name, char *value) {
    if (name && !is_graphics_key(name)) {
        std::lock_guard<std::mutex> lk(g_lock);
        auto it = g_spoof.find(name);
        if (it != g_spoof.end()) {
            // __system_property_get truncates to PROP_VALUE_MAX-1 (91) chars
            strncpy(value, it->second.c_str(), 91);
            value[91] = '\0';
            return (int) strlen(value);
        }
    }
    if (orig_system_property_get) {
        return orig_system_property_get(name, value);
    }
    value[0] = '\0';
    return 0;
}

// Wrapper cookie carrying the caller's original callback + cookie through our shim.
struct CbCtx {
    prop_read_cb_t orig_cb;
    void *orig_cookie;
};

static void my_prop_read_cb(void *cookie, const char *name, const char *value, uint32_t serial) {
    auto *ctx = (CbCtx *) cookie;
    const char *out = value;
    if (name && !is_graphics_key(name)) {
        std::lock_guard<std::mutex> lk(g_lock);
        auto it = g_spoof.find(name);
        if (it != g_spoof.end()) out = it->second.c_str();
    }
    ctx->orig_cb(ctx->orig_cookie, name, out, serial);
}

static void my_system_property_read_callback(const prop_info *pi, prop_read_cb_t cb, void *cookie) {
    CbCtx ctx{cb, cookie};
    if (orig_read_callback) orig_read_callback(pi, my_prop_read_cb, &ctx);
}

void install_property_get_hook() {
    if (g_installed) return;
    void* handle = xdl_open("libc.so", XDL_DEFAULT);
    if (handle) {
        // legacy path
        void* target = xdl_dsym(handle, "__system_property_get", nullptr);
        if (target) {
            DobbyHook(target, (void*)my_system_property_get, (void**)&orig_system_property_get);
        }
        // modern path (API 26+): SystemProperties.get() / Build.* read via this
        void* cbt = xdl_dsym(handle, "__system_property_read_callback", nullptr);
        if (cbt) {
            DobbyHook(cbt, (void*)my_system_property_read_callback, (void**)&orig_read_callback);
        }
        if (target || cbt) {
            g_installed = true;
            LOGD("Spoof hooks installed (get=%p read_callback=%p)", target, cbt);
        } else {
            LOGD("Spoof hook: no property symbols found");
        }
        xdl_close(handle);
    }
}

// ---- runtime configuration (called from BoxCore.cpp / NativeCore.spoofDevice) ------
extern "C" void vs_clear() {
    std::lock_guard<std::mutex> lk(g_lock);
    g_spoof.clear();
}

extern "C" void vs_set(const char *key, const char *value) {
    if (!key || !value) return;
    std::lock_guard<std::mutex> lk(g_lock);
    g_spoof[key] = value;
}

extern "C" void vs_ensure_installed() {
    install_property_get_hook();
}


__attribute__((constructor)) void init_virtual_spoof()
{
    // Install the hook early (before the guest's Build class initializes) but with an
    // empty map, so it is a no-op passthrough until spoofDevice() configures this clone.
    install_property_get_hook();
    LOGD("VirtualSpoof: __system_property_get hook loaded (idle until configured)");
}
