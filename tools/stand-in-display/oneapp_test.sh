#!/bin/bash
# Waits for mirroring (user shares Waze only and holds the phone sideways). At 12 s goes to the home
# screen for 5 s to see what the display gets when Waze is left, then back to Waze. Stops mirroring.
LOG=$1; T=$(cd "$(dirname "$0")" && pwd)
before=$(grep -c "recording http" "$LOG")
for i in $(seq 1 300); do now=$(grep -c "recording http" "$LOG"); [ "$now" -gt "$before" ] && break; sleep 1; done
[ "$now" -gt "$before" ] || { echo "mirroring did not start"; exit 1; }
done0=$(grep -c "RECORDING DONE" "$LOG")
echo "$(date +%T) recording started"
adb shell monkey -p com.waze -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 12
echo "$(date +%T) home screen (leaving Waze)"
adb shell input keyevent KEYCODE_HOME
sleep 5
echo "$(date +%T) back to Waze"
adb shell monkey -p com.waze -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
for i in $(seq 1 30); do [ "$(grep -c 'RECORDING DONE' "$LOG")" -gt "$done0" ] && break; sleep 1; done
adb shell am start -n com.screenshare.send/.MainActivity >/dev/null; sleep 2
python3 -I "$T/phone.py" tap "Stop"
echo "--- display log"; grep -vE "KB in" "$LOG" | tail -6
