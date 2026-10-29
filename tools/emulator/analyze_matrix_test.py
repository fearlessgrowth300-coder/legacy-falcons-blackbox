import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyze_matrix import (
    analyze_log, cold_start_failure, memory_unbounded, parse_total_time, start_limit_ms,
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
        self.assertTrue(any("FATAL EXCEPTION" in item for item in analyze_log(log)))

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
        self.assertEqual(start_limit_ms(34), 60000)
        self.assertIsNone(cold_start_failure("TotalTime: 1500\n", 34))
        self.assertIn("exceeded", cold_start_failure("TotalTime: 90000\n", 34))
        self.assertEqual(parse_total_time("Status: ok\nTotalTime: 42\n"), 42)
        self.assertIn("did not report", cold_start_failure("Status: ok\n", 30))

    def test_memory_growth(self):
        self.assertIsNone(memory_unbounded([100000, 110000, 120000, 130000]))
        self.assertIn("grew", memory_unbounded([100000, 110000, 400000]))
        self.assertIn("fewer", memory_unbounded([100000, 110000]))


if __name__ == "__main__":
    unittest.main()
