import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyze_matrix import (
    analyze_log, cold_start_failure, memory_unbounded, parse_total_time, start_limit_ms,
    tombstone_failures,
)

SOAK = []
for i in range(1, 13):
    SOAK.append("I GuestWebView: heartbeat %d" % i)
    SOAK.append("I GuestForeground: heartbeat %d" % i)
SOAK.append("I MATRIX: restart-begin pids=[10]")
SOAK.append("I GuestWebView: heartbeat 1")
SOAK.append("I GuestForeground: heartbeat 1")
SOAK.append("I MATRIX: restart-end pids=[11]")


class AnalyzeMatrixTest(unittest.TestCase):
    def test_clean_log_passes(self):
        self.assertEqual(analyze_log(SOAK), [])

    def test_fatal_fails(self):
        log = list(SOAK)
        log.insert(2, "E AndroidRuntime: FATAL EXCEPTION: main")
        log.insert(3, "E AndroidRuntime: Process: top.niunaijun.blackbox:p0, PID: 9")
        self.assertTrue(any("FATAL EXCEPTION" in item for item in analyze_log(log)))

    def test_unrelated_fatal_is_ignored(self):
        log = list(SOAK)
        log.insert(2, "F libc    : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 4053 (bt_stack_manage), pid 3887 (droid.bluetooth)")
        log.insert(3, "E ActivityManager: ANR in com.android.phone")
        self.assertEqual(analyze_log(log), [])

    def test_tombstone_must_mention_host(self):
        import tempfile
        root = tempfile.mkdtemp()
        with open(os.path.join(root, "tombstone_00"), "w", encoding="utf-8") as handle:
            handle.write("pid: 1, tid: 1, name: droid.bluetooth\n")
        self.assertEqual(tombstone_failures(root), [])
        with open(os.path.join(root, "tombstone_01"), "w", encoding="utf-8") as handle:
            handle.write("pid: 9, tid: 9, name: top.niunaijun.blackbox\n")
        self.assertTrue(tombstone_failures(root))

    def test_guest_death_outside_restart_fails(self):
        log = ["I ActivityManager: Process top.niunaijun.blackbox:p0 (pid 10) has died"] + list(SOAK)
        self.assertTrue(any("has died" in item for item in analyze_log(log)))

    def test_guest_death_during_restart_is_expected(self):
        log = list(SOAK)
        log.insert(-2, "I ActivityManager: Process top.niunaijun.blackbox:p0 (pid 10) has died")
        self.assertEqual(analyze_log(log), [])

    def test_host_death_always_fails(self):
        log = list(SOAK)
        log.insert(-1, "I ActivityManager: Process top.niunaijun.blackbox (pid 3) has died")
        self.assertTrue(any("top.niunaijun.blackbox (pid 3)" in item for item in analyze_log(log)))

    def test_cold_start_limits(self):
        self.assertEqual(start_limit_ms(26), 120000)
        self.assertEqual(start_limit_ms(34), 90000)
        self.assertIsNone(cold_start_failure("TotalTime: 1500\n", 34))
        self.assertIn("exceeded", cold_start_failure("TotalTime: 120000\n", 34))
        self.assertEqual(parse_total_time("Status: ok\nTotalTime: 42\n"), 42)
        self.assertIn("did not report", cold_start_failure("Status: ok\n", 30))

    def test_memory_growth(self):
        self.assertIsNone(memory_unbounded([100000, 110000, 120000, 130000]))
        self.assertIn("grew", memory_unbounded([100000, 110000, 400000]))
        self.assertIn("fewer", memory_unbounded([100000, 110000]))


if __name__ == "__main__":
    unittest.main()
