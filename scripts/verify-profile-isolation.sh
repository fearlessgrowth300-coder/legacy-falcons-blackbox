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
  echo "dumpsys package did not list two data directories."
  echo "Samsung One UI blocks shell queries into the work profile (pm list packages --user 10 raises SecurityException)."
  echo "Falling back to pm list users and dumpsys device_policy."
  USERS_OUT="$("$ADB" shell pm list users 2>&1 || true)"
  POLICY_OUT="$("$ADB" shell dumpsys device_policy 2>&1 || true)"
  printf '%s\n' "$USERS_OUT"
  if printf '%s\n' "$USERS_OUT" | grep -Eq 'UserInfo\{[1-9][0-9]*:' \
      && printf '%s\n' "$POLICY_OUT" | grep -q 'Profile Owner' \
      && printf '%s\n' "$POLICY_OUT" | grep -q "$PKG"; then
    echo
    echo "PASS: a second Android user exists and $PKG is a profile owner."
    echo "NOTE: data directories were not compared because the shell cannot read the work profile."
    echo "Hardware IMEI, the Samsung account, and the phone's public IP can still link the two users. A VPN is per Android user, so ShieldProxy must be installed in the work profile too."
    exit 0
  fi
  echo
  echo "FAIL: expected two data directories, or a second user with $PKG as profile owner."
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
