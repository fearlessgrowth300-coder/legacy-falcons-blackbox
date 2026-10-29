#!/usr/bin/env bash
# Soak the orig debug build and two in-repo guests on the booted emulator.
set -euo pipefail

API_LEVEL="${1:?api level}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOG_DIR="${ROOT}/emulator-logs/api-${API_LEVEL}"
mkdir -p "${LOG_DIR}"
ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB=(adb -s "${ANDROID_SERIAL}")
fi

fail() {
  echo "MATRIX_FAIL api=${API_LEVEL}: $*" | tee "${LOG_DIR}/failure.txt"
  exit 1
}

HOST_APK="$(find "${ROOT}/emulator-apks" -type f -name '*orig*x86_64*debug.apk' | head -1)"
TEST_APK="$(find "${ROOT}/emulator-apks" -type f -name '*androidTest*.apk' | head -1)"
[[ -n "${HOST_APK}" && -f "${HOST_APK}" ]] || fail "x86_64 host apk missing"
[[ -n "${TEST_APK}" && -f "${TEST_APK}" ]] || fail "androidTest apk missing"

"${ADB[@]}" install -r -t "${HOST_APK}"
"${ADB[@]}" install -r -t "${TEST_APK}"
"${ADB[@]}" shell pm grant top.niunaijun.blackbox android.permission.POST_NOTIFICATIONS || true
"${ADB[@]}" shell pm grant top.niunaijun.blackbox android.permission.READ_EXTERNAL_STORAGE || true
"${ADB[@]}" shell pm grant top.niunaijun.blackbox android.permission.WRITE_EXTERNAL_STORAGE || true

"${ADB[@]}" shell am force-stop top.niunaijun.blackbox || true
sleep 2
"${ADB[@]}" shell am start -W -n top.niunaijun.blackbox/top.niunaijun.blackboxa.view.main.WelcomeActivity \
  > "${LOG_DIR}/cold-start.txt" 2>&1 || true
python3 - << PY
import os, sys
sys.path.insert(0, "${ROOT}/tools/emulator")
from analyze_matrix import cold_start_failure
text = open("${LOG_DIR}/cold-start.txt", encoding="utf-8", errors="replace").read()
failure = cold_start_failure(text, ${API_LEVEL})
print(text)
if failure:
    open("${LOG_DIR}/failure.txt", "w").write(failure + "\n")
    sys.exit(1)
PY

"${ADB[@]}" logcat -c || true
LOGCAT_PID=""
"${ADB[@]}" logcat -v threadtime > "${LOG_DIR}/logcat.txt" &
LOGCAT_PID=$!
cleanup() {
  if [[ -n "${LOGCAT_PID}" ]]; then
    kill "${LOGCAT_PID}" 2>/dev/null || true
    wait "${LOGCAT_PID}" 2>/dev/null || true
  fi
  "${ADB[@]}" shell ls /data/tombstones > "${LOG_DIR}/tombstones.txt" 2>/dev/null || true
  "${ADB[@]}" pull /data/tombstones "${LOG_DIR}/tombstones" >/dev/null 2>&1 || true
  "${ADB[@]}" pull /data/anr "${LOG_DIR}/anr" >/dev/null 2>&1 || true
  "${ADB[@]}" shell dumpsys meminfo top.niunaijun.blackbox > "${LOG_DIR}/meminfo-final.txt" 2>/dev/null || true
}
trap cleanup EXIT

# Sample host+guest PSS while the instrumentation soak runs.
sample_memory() {
  local i=0
  while [[ "${i}" -lt 12 ]]; do
    "${ADB[@]}" shell dumpsys meminfo top.niunaijun.blackbox | awk '
      /TOTAL PSS:/ { print $3; exit }
      $1 == "TOTAL" { for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+$/) { print $i; exit } }
    ' >> "${LOG_DIR}/pss-kb.txt" || true
    i=$((i + 1))
    sleep 20
  done
}
sample_memory &
MEM_PID=$!

set +e
"${ADB[@]}" shell am instrument -w -r \
  -e class top.niunaijun.blackboxa.matrix.GuestMatrixTest \
  top.niunaijun.blackbox.test/androidx.test.runner.AndroidJUnitRunner \
  > "${LOG_DIR}/instrument.txt" 2>&1
INSTR_STATUS=$?
set -e
wait "${MEM_PID}" || true

if [[ "${INSTR_STATUS}" -ne 0 ]] || ! grep -q "OK (1 test)" "${LOG_DIR}/instrument.txt"; then
  fail "instrumentation failed (status ${INSTR_STATUS})"
fi

python3 "${ROOT}/tools/emulator/analyze_matrix.py" \
  "${LOG_DIR}/logcat.txt" \
  "${LOG_DIR}/pss-kb.txt" \
  "${LOG_DIR}/tombstones" \
  > "${LOG_DIR}/analysis.txt" || fail "$(cat "${LOG_DIR}/analysis.txt")"
echo "api ${API_LEVEL} analysis: $(cat "${LOG_DIR}/analysis.txt")"
