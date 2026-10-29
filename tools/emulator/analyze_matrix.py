"""Decide whether one emulator API level passed the guest soak."""

import os
import re
import sys

HOST = "top.niunaijun.blackbox"
CRASH_PATTERNS = (
    re.compile(r"FATAL EXCEPTION"),
    re.compile(r"Fatal signal"),
    re.compile(r"ANR in "),
    re.compile(r"am_anr:"),
    re.compile(r"am_crash:"),
    re.compile(r"\*\*\* \*\*\* \*\*\* \*\*\* \*\*\* \*\*\*"),
)
HOST_DEATH = re.compile(
    r"Process " + re.escape(HOST) + r" \(pid \d+\) has died"
)
GUEST_DEATH = re.compile(
    r"Process " + re.escape(HOST) + r":p\d+ \(pid \d+\) has died"
)


def start_limit_ms(api_level):
    if api_level <= 28:
        return 120_000
    if api_level <= 30:
        return 90_000
    return 60_000


def parse_total_time(am_start_output):
    for line in am_start_output.splitlines():
        if line.strip().startswith("TotalTime:"):
            return int(line.split(":", 1)[1].strip())
    return None


def cold_start_failure(am_start_output, api_level):
    total = parse_total_time(am_start_output)
    if total is None:
        return "cold start did not report TotalTime"
    limit = start_limit_ms(api_level)
    if total > limit:
        return "cold start %d ms exceeded %d ms" % (total, limit)
    return None


def memory_unbounded(samples, growth_kb=64 * 1024, ratio=1.5):
    """samples are total PSS in KB. The second sample is the settled baseline."""
    if len(samples) < 3:
        return "fewer than 3 memory samples"
    baseline = samples[1]
    for sample in samples[2:]:
        if sample > baseline + growth_kb and sample > baseline * ratio:
            return "PSS grew from %d KB to %d KB" % (baseline, sample)
    return None


def _heartbeat_count(lines, tag):
    needle = tag
    return sum(1 for line in lines if needle in line and "heartbeat" in line)


def analyze_log(lines):
    """Return a list of failure strings. Empty means the log is clean."""
    failures = []
    in_restart = False
    for line in lines:
        if "MATRIX" in line and "restart-begin" in line:
            in_restart = True
        elif "MATRIX" in line and "restart-end" in line:
            in_restart = False
        for pattern in CRASH_PATTERNS:
            if pattern.search(line):
                failures.append(line.strip()[:400])
                break
        else:
            if HOST_DEATH.search(line):
                failures.append(line.strip()[:400])
            elif not in_restart and GUEST_DEATH.search(line):
                failures.append(line.strip()[:400])
    before = []
    after = []
    seen_restart = False
    for line in lines:
        if "MATRIX" in line and "restart-begin" in line:
            seen_restart = True
            continue
        if not seen_restart:
            before.append(line)
        else:
            after.append(line)
    if _heartbeat_count(before, "GuestWebView") < 8:
        failures.append("GuestWebView heartbeats during soak: %d" % _heartbeat_count(before, "GuestWebView"))
    if _heartbeat_count(before, "GuestForeground") < 8:
        failures.append("GuestForeground heartbeats during soak: %d" % _heartbeat_count(before, "GuestForeground"))
    if not seen_restart:
        failures.append("restart phase was not logged")
    elif _heartbeat_count(after, "GuestWebView") < 1 or _heartbeat_count(after, "GuestForeground") < 1:
        failures.append("a guest did not heartbeat after restart")
    return failures


def tombstones_present(path):
    if not path or not os.path.isdir(path):
        return False
    for _root, _dirs, files in os.walk(path):
        if files:
            return True
    return False


def main(argv):
    log_path, pss_path, tombstone_dir = argv[1], argv[2], argv[3]
    lines = open(log_path, encoding="utf-8", errors="replace").read().splitlines()
    failures = analyze_log(lines)
    samples = []
    if os.path.isfile(pss_path):
        for line in open(pss_path, encoding="utf-8", errors="replace"):
            line = line.strip()
            if line.isdigit():
                samples.append(int(line))
    memory = memory_unbounded(samples)
    if memory:
        failures.append(memory)
    if tombstones_present(tombstone_dir):
        failures.append("tombstone files were produced")
    if failures:
        print("\n".join(failures))
        return 1
    print("passed; pss samples %s" % samples)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
