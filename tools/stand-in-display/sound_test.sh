#!/bin/bash
# Waits for mirroring, lets one 20 s recording finish, stops mirroring, then checks the sound.
LOG=$1; T=$(cd "$(dirname "$0")" && pwd)
before=$(grep -c "recording http" "$LOG"); done0=$(grep -c "RECORDING DONE" "$LOG")
for i in $(seq 1 300); do now=$(grep -c "recording http" "$LOG"); [ "$now" -gt "$before" ] && break; sleep 1; done
[ "$now" -gt "$before" ] || { echo "mirroring did not start"; exit 1; }
echo "$(date +%T) recording started"
for i in $(seq 1 40); do [ "$(grep -c 'RECORDING DONE' "$LOG")" -gt "$done0" ] && break; sleep 1; done
adb shell am start -n com.screenshare.send/.MainActivity >/dev/null; sleep 2
python3 -I "$T/phone.py" tap "Stop"
echo "--- display log"; grep -vE "KB in" "$LOG" | tail -6
