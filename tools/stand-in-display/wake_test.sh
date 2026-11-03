#!/bin/bash
# Waits for mirroring, then checks the screen stays on past a 15 s timeout. Restores the timeout.
LOG=$1; ORIG=$2; T=$(cd "$(dirname "$0")" && pwd)
before=$(grep -c "recording http" "$LOG")
for i in $(seq 1 300); do now=$(grep -c "recording http" "$LOG"); [ "$now" -gt "$before" ] && break; sleep 1; done
[ "$now" -gt "$before" ] || { echo "mirroring did not start"; exit 1; }
echo "$(date +%T) mirroring started"
echo "--- wake locks held by the app:"; adb shell dumpsys power | grep -i "ScreenShare:mirror"
adb shell settings put system screen_off_timeout 15000
echo "$(date +%T) screen timeout set to 15 s; waiting 30 s"
sleep 30
echo "--- after 30 s:"; adb shell dumpsys power | grep -E "mWakefulness=|Display Power: state="
adb shell settings put system screen_off_timeout "$ORIG"
echo "screen timeout restored to $(adb shell settings get system screen_off_timeout) ms"
adb shell am start -n com.screenshare.send/.MainActivity >/dev/null; sleep 2
python3 -I "$T/phone.py" tap "Stop"; sleep 2
echo "--- wake locks after stop (should be none):"; adb shell dumpsys power | grep -ci "ScreenShare:mirror"
