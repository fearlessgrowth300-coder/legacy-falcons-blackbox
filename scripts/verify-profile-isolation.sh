#!/bin/sh
# Compare the two Android users that can hold Blackbox.
# Usage: scripts/verify-profile-isolation.sh [package] 
# package defaults to top.niunaijun.blackbox. Requires adb on PATH.
set -u
PKG="${1:-top.niunaijun.blackbox}"
ADB="${ADB:-adb}"

echo "== Android users =="
"$ADB" shell pm list users || exit 1

echo
echo "== $PKG records =="
DUMP="$("$ADB" shell dumpsys package "$PKG")" || exit 1
printf '%s\n' "$DUMP" | grep -E 'userId=|dataDir=|codePath=' || true

USERS="$(printf '%s\n' "$DUMP" | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | sort -u)"
DIRS="$(printf '%s\n' "$DUMP" | sed -n 's/.*dataDir=\([^ ]*\).*/\1/p' | sort -u)"
echo
echo "Parsed uids:"
printf '%s\n' "$USERS"
echo "Parsed data dirs:"
printf '%s\n' "$DIRS"

COUNT="$(printf '%s\n' "$DIRS" | grep -c . || true)"
if [ "$COUNT" -lt 2 ]; then
  echo
  echo "FAIL: expected two data directories. The separate Android profile is not installed, or dumpsys did not list both users."
  exit 2
fi

FIRST="$(printf '%s\n' "$DIRS" | sed -n '1p')"
SECOND="$(printf '%s\n' "$DIRS" | sed -n '2p')"
if [ "$FIRST" = "$SECOND" ]; then
  echo "FAIL: both sides report the same data directory: $FIRST"
  exit 3
fi
echo
echo "PASS: data directories differ"
echo "  $FIRST"
echo "  $SECOND"

echo
echo "== cross-read check (shell uid; Permission denied is the expected result) =="
for DIR in $FIRST $SECOND; do
  echo "-- $DIR"
  "$ADB" shell ls "$DIR" >/tmp/bb-profile-ls.txt 2>/tmp/bb-profile-ls.err || true
  if grep -qi 'permission denied\|not permitted' /tmp/bb-profile-ls.err; then
    echo "PASS: shell cannot read $DIR"
  elif [ ! -s /tmp/bb-profile-ls.txt ]; then
    echo "PASS: no listing returned for $DIR"
  else
    echo "NOTE: shell could list $DIR. That is the adb shell uid, not proof that one Blackbox process can read the other."
  fi
done

echo
echo "Hardware IMEI, the Samsung account, and the phone's public IP can still link the two users. A VPN is per Android user, so ShieldProxy must be installed in the work profile too."
exit 0
