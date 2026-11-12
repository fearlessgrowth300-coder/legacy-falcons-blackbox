#pragma once

#include <atomic>
#include <cstdint>
#include <unistd.h>

// Per-thread reentry without emulated TLS. thread_local on this NDK calls
// malloc via __emutls_get_address the first time a thread touches it. The
// syscall hook runs inside jemalloc's own futex, so that malloc recurses
// and SIGSEGVs. These slots are static and the fast path never touches them.

inline bool syscall_number_is_fast_path(long number, long nr_connect) {
    return number != nr_connect;
}

template <int Tag>
class HookReentry {
public:
    struct Guard {
        bool armed;
        Guard() : armed(false) {
            if (enter()) armed = true;
        }
        ~Guard() {
            if (armed) leave();
        }
        Guard(const Guard &) = delete;
        Guard &operator=(const Guard &) = delete;
    };

    static int depth() {
        Slot *s = find((int32_t) gettid(), false);
        return s == nullptr ? 0 : s->depth.load(std::memory_order_relaxed);
    }

private:
    struct Slot {
        std::atomic<int32_t> tid;
        std::atomic<int32_t> depth;
        Slot() : tid(0), depth(0) {}
    };

    static Slot table[128];

    static Slot *find(int32_t tid, bool create) {
        if (tid <= 0) return nullptr;
        uint32_t start = (uint32_t) tid & 127u;
        for (uint32_t n = 0; n < 128; n++) {
            Slot *s = &table[(start + n) & 127u];
            int32_t cur = s->tid.load(std::memory_order_acquire);
            if (cur == tid) return s;
            if (!create || cur != 0) continue;
            int32_t empty = 0;
            if (s->tid.compare_exchange_strong(empty, tid, std::memory_order_acq_rel)) {
                return s;
            }
        }
        return nullptr;
    }

    static bool enter() {
        Slot *s = find((int32_t) gettid(), true);
        if (s == nullptr) return false;
        s->depth.fetch_add(1, std::memory_order_relaxed);
        return true;
    }

    static void leave() {
        Slot *s = find((int32_t) gettid(), false);
        if (s == nullptr) return;
        int prev = s->depth.load(std::memory_order_relaxed);
        if (prev > 0) s->depth.fetch_sub(1, std::memory_order_relaxed);
    }
};

template <int Tag>
typename HookReentry<Tag>::Slot HookReentry<Tag>::table[128];
