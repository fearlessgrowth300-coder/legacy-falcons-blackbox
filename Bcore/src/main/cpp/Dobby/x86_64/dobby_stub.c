/* Passthrough Dobby for the x86_64 emulator ABI.
 * The real static library ships only for armeabi-v7a and arm64-v8a. Returning the
 * original address and success lets libblackbox.so load and keep calling the real
 * libc/libart functions. Nothing is patched, so a missing ARM trampoline cannot
 * SIGSEGV the host or a guest on an x86_64 image.
 */
#include <string.h>

int DobbyHook(void *address, void *replace_call, void **origin_call) {
    (void) replace_call;
    if (origin_call != NULL) {
        *origin_call = address;
    }
    return address == NULL ? -1 : 0;
}

int DobbyInstrument(void *address, void *dbi_call) {
    (void) address;
    (void) dbi_call;
    return -1;
}

int DobbyDestroy(void *address) {
    (void) address;
    return 0;
}

void *DobbySymbolResolver(const char *image_name, const char *symbol_name) {
    (void) image_name;
    (void) symbol_name;
    return NULL;
}

int DobbyGlobalOffsetTableReplace(char *image_name, char *symbol_name, void *fake_func, void **orig_func) {
    (void) image_name;
    (void) symbol_name;
    (void) fake_func;
    (void) orig_func;
    return -1;
}

const char *DobbyBuildVersion(void) {
    return "x86_64-passthrough";
}

void dobby_enable_near_branch_trampoline(void) {}
void dobby_disable_near_branch_trampoline(void) {}
void dobby_register_image_load_callback(void *func) { (void) func; }

void log_set_level(int level) { (void) level; }
void log_switch_to_syslog(void) {}
void log_switch_to_file(const char *path) { (void) path; }
