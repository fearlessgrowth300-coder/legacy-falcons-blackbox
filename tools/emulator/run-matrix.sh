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

install_apk() {
  local apk="$1"
  local attempt
  for attempt in 1 2 3; do
    if "${ADB[@]}" install -r -t "${apk}"; then
      return 0
    fi
    echo "install attempt ${attempt} failed for ${apk}; retrying" >&2
    sleep 5
  done
  return 1
}
install_apk "${HOST_APK}"
install_apk "${TEST_APK}"
# POST_NOTIFICATIONS exists only from API 33. WRITE_EXTERNAL_STORAGE is not requested
# once the host manifest caps it at API 29. A rejected grant must not abort the soak.
if [[ "${API_LEVEL}" -ge 33 ]]; then
  "${ADB[@]}" shell pm grant top.niunaijun.blackbox android.permission.POST_NOTIFICATIONS || true
fi
"${ADB[@]}" shell pm grant top.niunaijun.blackbox android.permission.READ_EXTERNAL_STORAGE || true
if [[ "${API_LEVEL}" -le 29 ]]; then
  "${ADB[@]}" shell pm grant top.niunaijun.blackbox android.permission.WRITE_EXTERNAL_STORAGE || true
fi

LOGCAT_PID=""
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
"${ADB[@]}" logcat -c || true
"${ADB[@]}" logcat -v threadtime > "${LOG_DIR}/logcat.txt" &
LOGCAT_PID=$!

# WelcomeActivity finishes immediately and opens sign-in, so `am start -W` reports
# Status: timeout and never prints TotalTime. Time until the host process exists
# instead, using the same per-API budget.
"${ADB[@]}" shell am force-stop top.niunaijun.blackbox || true
sleep 1
START_MS="$(date +%s%3N)"
"${ADB[@]}" shell am start -n top.niunaijun.blackbox/top.niunaijun.blackboxa.view.main.WelcomeActivity \
  > "${LOG_DIR}/am-start.txt" 2>&1 || true
LIMIT_MS="$(python3 -c "import sys; sys.path.insert(0, '${ROOT}/tools/emulator'); from analyze_matrix import start_limit_ms; print(start_limit_ms(${API_LEVEL}))")"
HOST_PID=""
while true; do
  NOW_MS="$(date +%s%3N)"
  ELAPSED_MS="$((NOW_MS - START_MS))"
  HOST_PID="$("${ADB[@]}" shell pidof top.niunaijun.blackbox 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "${HOST_PID}" ]]; then
    HOST_PID="$("${ADB[@]}" shell ps 2>/dev/null | awk '/top\.niunaijun\.blackbox$/ {print $2; exit}' | tr -d '\r' || true)"
  fi
  if [[ -n "${HOST_PID}" ]]; then
    break
  fi
  if [[ "${ELAPSED_MS}" -ge "${LIMIT_MS}" ]]; then
    {
      echo "cold start exceeded ${LIMIT_MS} ms without a host process"
      echo "--- am start ---"
      cat "${LOG_DIR}/am-start.txt" || true
    } | tee "${LOG_DIR}/failure.txt"
    exit 1
  fi
  sleep 1
done
sleep 2
STILL_PID="$("${ADB[@]}" shell pidof top.niunaijun.blackbox 2>/dev/null | tr -d '\r' || true)"
if [[ -z "${STILL_PID}" ]]; then
  echo "host process exited during cold start" | tee "${LOG_DIR}/failure.txt"
  exit 1
fi
{
  echo "TotalTime: ${ELAPSED_MS}"
  echo "pid: ${HOST_PID}"
  cat "${LOG_DIR}/am-start.txt"
} | tee "${LOG_DIR}/cold-start.txt"
python3 - << PY
import sys
sys.path.insert(0, "${ROOT}/tools/emulator")
from analyze_matrix import cold_start_failure
text = open("${LOG_DIR}/cold-start.txt", encoding="utf-8", errors="replace").read()
failure = cold_start_failure(text, ${API_LEVEL})
if failure:
    open("${LOG_DIR}/failure.txt", "w").write(failure + "\n")
    sys.exit(1)
PY

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
