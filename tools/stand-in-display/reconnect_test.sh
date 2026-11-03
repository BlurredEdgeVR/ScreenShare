#!/bin/bash
# Car mode start -> recording 1 -> stand-in hangs up -> expect automatic recast (recording 2)
# -> phone Wi-Fi off 8 s -> expect recast after rejoin (recording 3) -> Stop from the notification.
LOG=$1; T=$(cd "$(dirname "$0")" && pwd)
rec() { grep -c "recording http" "$LOG"; }
done_n() { grep -c "RECORDING DONE" "$LOG"; }
wait_rec() { local want=$1 secs=$2; for i in $(seq 1 $secs); do [ "$(rec)" -ge "$want" ] && return 0; sleep 1; done; return 1; }
wait_done() { local want=$1 secs=$2; for i in $(seq 1 $secs); do [ "$(done_n)" -ge "$want" ] && return 0; sleep 1; done; return 1; }
r0=$(rec); d0=$(done_n)
echo "$(date +%T) waiting for the share tap"
wait_rec $((r0+1)) 300 || { echo "mirroring never started"; exit 1; }
echo "$(date +%T) 1st cast playing; in front: $(sleep 4; adb shell dumpsys window | grep -oE 'mCurrentFocus=[^ ]+ [^ ]+ [^ }]+' | head -1)"
wait_done $((d0+1)) 45; t_drop=$(date +%s); echo "$(date +%T) stand-in hung up"
if wait_rec $((r0+2)) 40; then echo "$(date +%T) RECONNECTED by itself after $(( $(date +%s) - t_drop )) s"; else echo "$(date +%T) no reconnect after the hang-up"; fi
sleep 8
echo "$(date +%T) phone Wi-Fi OFF"; adb shell cmd wifi set-wifi-enabled disabled; sleep 8
echo "$(date +%T) phone Wi-Fi ON"; adb shell cmd wifi set-wifi-enabled enabled; t_on=$(date +%s)
if wait_rec $((r0+3)) 90; then echo "$(date +%T) RECONNECTED after Wi-Fi came back, $(( $(date +%s) - t_on )) s after switching it on"; else echo "$(date +%T) no reconnect after the Wi-Fi drop"; fi
sleep 6
echo "--- notification text:"; adb shell dumpsys notification --noredact | grep -A40 "pkg=com.screenshare.send" | grep -E "android.text=|actions=" | head -3
# Open the shade, expand ScreenShare's notification (Samsung hides buttons until expanded), tap Stop.
adb shell input swipe 540 10 540 1600 300; sleep 2
xy=$(python3 -I "$T/phone.py" find "ScreenShare mirroring")
if [ -n "$xy" ]; then set -- $xy; adb shell input swipe $1 $2 $1 $(( $2 + 500 )) 300; sleep 2; fi
python3 -I "$T/phone.py" tap "Stop"; sleep 3; adb shell cmd statusbar collapse
echo "mirroring still running after notification Stop: $(adb shell dumpsys activity services com.screenshare.send | grep -c MirrorService)"
echo "--- display log"; grep -vE "KB in" "$LOG" | grep -E "SOAP|recording http|DONE|closed|no data" | tail -14
