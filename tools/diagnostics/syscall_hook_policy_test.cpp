#include "../../Bcore/src/main/cpp/HookReentry.h"

#include <cstdio>
#include <cstdlib>

static int checks;

static void check(bool ok, const char *label) {
    checks++;
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", label);
        std::exit(1);
    }
}

int main() {
    const long nr_connect = 203;
    check(syscall_number_is_fast_path(98, nr_connect), "futex does not touch hook thread state");
    check(syscall_number_is_fast_path(222, nr_connect), "mmap does not touch hook thread state");
    check(syscall_number_is_fast_path(64, nr_connect), "write does not touch hook thread state");
    check(!syscall_number_is_fast_path(nr_connect, nr_connect), "connect is the slow path");
    std::printf("PASS: %d syscall hook checks\n", checks);
    return 0;
}
