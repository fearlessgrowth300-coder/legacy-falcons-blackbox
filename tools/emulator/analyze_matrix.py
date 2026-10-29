"""Decide whether one emulator API level passed the guest soak."""

import os
import re
import sys

HOST = "top.niunaijun.blackbox"
HOST_DEATH = re.compile(
    r"Process " + re.escape(HOST) + r" \(pid \d+\) has died"
)
SERVER_DEATH = re.compile(
    r"Process " + re.escape(HOST) + r":black \(pid \d+\) has died"
)
GUEST_DEATH = re.compile(
    r"Process " + re.escape(HOST) + r":p\d+ \(pid \d+\) has died"
)


def start_limit_ms(api_level):
    # google_apis images spend a long time in bindApplication while GMS is
    # still settling. 90s is still a failed start, not a hang we ignore.
    if api_level <= 28:
        return 120_000
    return 90_000


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


def _app_fault(lines, index):
    """True when a crash/ANR line belongs to the host package, not the emulator."""
    line = lines[index]
    if HOST in line and (
        "Fatal signal" in line
        or "ANR in " in line
        or "am_anr:" in line
        or "am_crash:" in line
        or "FATAL EXCEPTION" in line
    ):
        return True
    if "FATAL EXCEPTION" in line or "*** *** ***" in line:
        window = "\n".join(lines[index:index + 12])
        return HOST in window
    return False


def analyze_log(lines):
    """Return a list of failure strings. Empty means the log is clean."""
    failures = []
    in_restart = False
    test_finished = False
    for index, line in enumerate(lines):
        if "TestRunner" in line and "finished:" in line:
            test_finished = True
        if "MATRIX" in line and "restart-begin" in line:
            in_restart = True
        elif "MATRIX" in line and "restart-end" in line:
            in_restart = False
        if _app_fault(lines, index):
            failures.append(line.strip()[:400])
            continue
        if test_finished:
            continue
        if HOST_DEATH.search(line) or SERVER_DEATH.search(line) or (
            not in_restart and GUEST_DEATH.search(line)
        ):
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


def tombstone_failures(path):
    """Tombstones from bluetooth and system_server are emulator noise."""
    if not path or not os.path.isdir(path):
        return []
    failures = []
    for root, _dirs, files in os.walk(path):
        for name in files:
            full = os.path.join(root, name)
            try:
                with open(full, encoding="utf-8", errors="replace") as handle:
                    text = handle.read(200_000)
            except OSError:
                continue
            if HOST in text:
                failures.append("tombstone %s mentions %s" % (name, HOST))
    return failures


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
    failures.extend(tombstone_failures(tombstone_dir))
    if failures:
        print("\n".join(failures))
        return 1
    print("passed; pss samples %s" % samples)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
