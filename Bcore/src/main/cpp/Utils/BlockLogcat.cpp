// Blocks a guest app from spawning `logcat`. Meta apps (Instagram/WhatsApp/Facebook) ship a
// native crash reporter (libnpth) that runs `logcat` on launch to capture logs. Because guests
// run under BlackBox's UID, Android 13 pops "Allow BlackBox to access all device logs?" every
// time. We hook the exec* family and fail any attempt to exec `logcat` (crash reporting is
// non-critical, so the app carries on) — only "logcat" is touched, nothing else.
#include <unistd.h>
#include <spawn.h>
#include <cstring>
#include <cerrno>
#include <android/log.h>
#include "./xdl.h"
#include "Dobby/dobby.h"

#define LOG_TAG "BlockLogcat"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static int (*orig_execv)(const char *, char *const[]) = nullptr;
static int (*orig_execve)(const char *, char *const[], char *const[]) = nullptr;
static int (*orig_execvp)(const char *, char *const[]) = nullptr;
static int (*orig_posix_spawn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                               const posix_spawnattr_t *, char *const[], char *const[]) = nullptr;
static bool g_bl_installed = false;

static bool is_logcat(const char *p) {
    if (!p) return false;
    const char *b = strrchr(p, '/');
    b = b ? b + 1 : p;
    return strcmp(b, "logcat") == 0;
}

static int my_execv(const char *path, char *const argv[]) {
    if (is_logcat(path) || (argv && is_logcat(argv[0]))) { errno = EACCES; return -1; }
    return orig_execv(path, argv);
}
static int my_execve(const char *path, char *const argv[], char *const envp[]) {
    if (is_logcat(path) || (argv && is_logcat(argv[0]))) { errno = EACCES; return -1; }
    return orig_execve(path, argv, envp);
}
static int my_execvp(const char *file, char *const argv[]) {
    if (is_logcat(file) || (argv && is_logcat(argv[0]))) { errno = EACCES; return -1; }
    return orig_execvp(file, argv);
}
static int my_posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                          const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (is_logcat(path) || (argv && is_logcat(argv[0]))) { errno = EACCES; return -1; }
    return orig_posix_spawn(pid, path, fa, attr, argv, envp);
}

extern "C" void bl_install() {
    if (g_bl_installed) return;
    void *h = xdl_open("libc.so", XDL_DEFAULT);
    if (!h) return;
    void *t;
    if ((t = xdl_dsym(h, "execv", nullptr)))       DobbyHook(t, (void *) my_execv,       (void **) &orig_execv);
    if ((t = xdl_dsym(h, "execve", nullptr)))      DobbyHook(t, (void *) my_execve,      (void **) &orig_execve);
    if ((t = xdl_dsym(h, "execvp", nullptr)))      DobbyHook(t, (void *) my_execvp,      (void **) &orig_execvp);
    if ((t = xdl_dsym(h, "posix_spawn", nullptr))) DobbyHook(t, (void *) my_posix_spawn, (void **) &orig_posix_spawn);
    xdl_close(h);
    g_bl_installed = true;
    LOGD("logcat-block hooks installed");
}
