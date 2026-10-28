



#include "IO.h"
#include "Log.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

jmethodID getAbsolutePathMethodId;

list<IO::RelocateInfo> relocate_rule;

static int g_iso_user = -1;
static std::string g_iso_root;
static std::string g_iso_ext;
static thread_local char g_iso_buf[4096];
static thread_local char g_iso_deny[4096];

static std::string strip_slash(std::string value) {
    while (value.size() > 1 && value.back() == '/') value.pop_back();
    return value;
}

void IO::setIsolation(const char *virtualRoot, const char *externalRoot, int userId) {
    g_iso_user = userId;
    g_iso_root = strip_slash(virtualRoot ? virtualRoot : "");
    g_iso_ext = strip_slash(externalRoot ? externalRoot : "");
}

static bool normalize_path(const char *in, char *out, size_t outlen) {
    if (!in || !out || outlen < 2) return false;
    bool absolute = in[0] == '/';
    std::vector<std::string> parts;
    std::string current;
    for (const char *p = in;; ++p) {
        if (*p == '/' || *p == '\0') {
            if (current == "..") {
                if (!parts.empty()) parts.pop_back();
            } else if (!current.empty() && current != ".") {
                parts.push_back(current);
            }
            current.clear();
            if (*p == '\0') break;
            continue;
        }
        current.push_back(*p);
    }
    std::string built = absolute ? "/" : "";
    for (size_t i = 0; i < parts.size(); i++) {
        if (built.empty() || built == "/") built += parts[i];
        else {
            built.push_back('/');
            built += parts[i];
        }
    }
    if (absolute && built.empty()) built = "/";
    if (built.size() + 1 > outlen) return false;
    memcpy(out, built.c_str(), built.size() + 1);
    return true;
}

static bool under_root(const char *path, const std::string &root) {
    if (root.empty() || !path) return false;
    if (strncmp(path, root.c_str(), root.size()) != 0) return false;
    return path[root.size()] == '\0' || path[root.size()] == '/';
}

static bool id_matches(const char *rest, int user) {
    char expected[16];
    snprintf(expected, sizeof(expected), "%d", user);
    size_t n = strlen(expected);
    if (strncmp(rest, expected, n) != 0) return false;
    return rest[n] == '\0' || rest[n] == '/';
}

static const char *deny_path() {
    snprintf(g_iso_deny, sizeof(g_iso_deny), "%s/data/user/%d/.isolation-denied",
             g_iso_root.empty() ? "/data/local/tmp" : g_iso_root.c_str(), g_iso_user);
    return g_iso_deny;
}

// 1 allow, 0 deny, -1 not our tree
static int confine_blackbox(const char *path) {
    const char *marker = strstr(path, "/blackbox/");
    if (!marker) return -1;
    const char *rel = marker + strlen("/blackbox/");
    if (strcmp(rel, "system") == 0 || strncmp(rel, "system/", 7) == 0) return 0;
    if (strncmp(rel, "data/user/", 10) == 0) return id_matches(rel + 10, g_iso_user) ? 1 : 0;
    if (strncmp(rel, "data/user_de/", 13) == 0) return id_matches(rel + 13, g_iso_user) ? 1 : 0;
    if (strncmp(rel, "storage/emulated/", 17) == 0) return id_matches(rel + 17, g_iso_user) ? 1 : 0;
    if (strncmp(rel, "profiles/", 9) == 0) {
        const char *cur = strstr(rel, "/");
        if (!cur) return 0;
        const char *id = strchr(cur + 1, '/');
        if (!id) return 0;
        return id_matches(id + 1, g_iso_user) ? 1 : 0;
    }
    if (strncmp(rel, "data/app", 8) == 0 || strncmp(rel, "proc", 4) == 0 || strncmp(rel, "cache", 5) == 0)
        return 1;
    if (under_root(path, g_iso_root) || under_root(path, g_iso_ext)) return 0;
    return -1;
}

static const char *confine_path(const char *path) {
    if (g_iso_user < 0 || !path) return path;
    if (!strstr(path, "/blackbox")) return path;
    if (!normalize_path(path, g_iso_buf, sizeof(g_iso_buf))) return deny_path();
    if ((!g_iso_root.empty() && strcmp(g_iso_buf, g_iso_root.c_str()) == 0)
            || (!g_iso_ext.empty() && strcmp(g_iso_buf, g_iso_ext.c_str()) == 0)) {
        return deny_path();
    }
    int verdict = confine_blackbox(g_iso_buf);
    if (verdict == 0) return deny_path();
    if (verdict == 1) return g_iso_buf;
    return path;
}

char *replace(const char *str, const char *src, const char *dst) {
    const char *pos = str;
    int count = 0;
    while ((pos = strstr(pos, src))) {
        count++;
        pos += strlen(src);
    }

    size_t source_len = strlen(src);
    size_t destination_len = strlen(dst);
    size_t result_len = strlen(str) - (source_len * count)
            + (destination_len * count) + 1;
    char *result = (char *) malloc(result_len);
    if (result == nullptr) {
        return nullptr;
    }
    memset(result, 0, result_len);

    const char *left = str;
    const char *right = nullptr;

    while ((right = strstr(left, src))) {
        strncat(result, left, right - left);
        strcat(result, dst);
        right += strlen(src);
        left = right;
    }
    strcat(result, left);
    return result;
}

const char *IO::redirectPath(const char *__path) {
    if (__path == nullptr) return __path;
    const char *confined = confine_path(__path);
    if (confined != __path) return confined;

    if (strstr(__path, "resource-cache")) {
        ALOGD("Blocking resource-cache path: %s", __path);
        return "/dev/null";
    }
    
    
    if (strstr(__path, "@idmap")) {
        ALOGD("Blocking idmap path: %s", __path);
        return "/dev/null";
    }
    
    
    if (strstr(__path, "systemui") && (strstr(__path, ".frro") || strstr(__path, "-accent-") || strstr(__path, "-dynamic-") || strstr(__path, "-neutral-"))) {
        ALOGD("Blocking systemui problematic path: %s", __path);
        return "/dev/null";
    }
    
    
    if (strstr(__path, "data@resource-cache@")) {
        ALOGD("Blocking data@resource-cache@ pattern: %s", __path);
        return "/dev/null";
    }
    
    
    if (strstr(__path, ".frro")) {
        ALOGD("Blocking .frro file: %s", __path);
        return "/dev/null";
    }
    
    
    if (strstr(__path, "systemui")) {
        ALOGD("Blocking systemui path: %s", __path);
        return "/dev/null";
    }

    list<IO::RelocateInfo>::iterator iterator;
    for (iterator = relocate_rule.begin(); iterator != relocate_rule.end(); ++iterator) {
        IO::RelocateInfo info = *iterator;
        if (strstr(__path, info.targetPath) && !strstr(__path, "/blackbox/")) {
            char *ret = replace(__path, info.targetPath, info.relocatePath);
            if (ret == nullptr) return __path;
            const char *after = confine_path(ret);
            if (after != ret) free(ret);
            return after;
        }
    }
    return __path;
}

jstring IO::redirectPath(JNIEnv *env, jstring path) {




    return BoxCore::redirectPathString(env, path);
}

jobject IO::redirectPath(JNIEnv *env, jobject path) {






    return BoxCore::redirectPathFile(env, path);
}

void IO::addRule(const char *targetPath, const char *relocatePath) {
    IO::RelocateInfo info{};
    info.targetPath = targetPath;
    info.relocatePath = relocatePath;
    relocate_rule.push_back(info);
}

void IO::init(JNIEnv *env) {
    jclass tmpFile = env->FindClass("java/io/File");
    getAbsolutePathMethodId = env->GetMethodID(tmpFile, "getAbsolutePath", "()Ljava/lang/String;");
}
