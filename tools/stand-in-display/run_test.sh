#!/bin/bash
# Waits for mirroring to start, records at full speed then as a slow car, with scrolling on the
# phone so the screen keeps changing, then stops mirroring.  Usage: run_test.sh DISPLAY_LOG
LOG=$1
T=$(cd "$(dirname "$0")" && pwd)
before=$(grep -c "recording http" "$LOG")
echo "waiting for mirroring to start"
for i in $(seq 1 300); do now=$(grep -c "recording http" "$LOG"); [ "$now" -gt "$before" ] && break; sleep 1; done
[ "$now" -gt "$before" ] || { echo "mirroring did not start within 5 minutes"; exit 1; }
done0=$(grep -c "RECORDING DONE" "$LOG")
motion() {
  adb shell am start -a android.settings.SETTINGS >/dev/null; sleep 1
  local end=$((SECONDS + $1))
  while [ $SECONDS -lt $end ]; do
    adb shell input swipe 540 1900 540 700 200; adb shell input swipe 540 700 540 1900 200
  done
}
wait_done() { for i in $(seq 1 60); do [ "$(grep -c 'RECORDING DONE' "$LOG")" -ge "$1" ] && return 0; sleep 1; done; return 1; }
echo "recording 1: full speed"
motion 17; wait_done $((done0 + 1))
echo "recording 2: slow car (1.2 Mbps)"
curl -s "http://127.0.0.1:49152/record?rate=1200&seconds=20"; echo
motion 17; wait_done $((done0 + 2))
echo "stopping mirroring"
adb shell am start -n com.screenshare.send/.MainActivity >/dev/null; sleep 2
python3 -I "$T/phone.py" tap "Stop"
sleep 1
echo "--- display log"; tail -n 24 "$LOG"
