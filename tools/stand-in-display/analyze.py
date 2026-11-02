#!/usr/bin/env python3
"""Summarise a recorded mirror stream: resolution, frame rate, bitrate, keyframes, gaps, errors."""
import json
import subprocess
import sys


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def analyze(path):
    info = json.loads(run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                           "stream=codec_name,width,height,profile", "-of", "json", path]).stdout or "{}")
    st = (info.get("streams") or [{}])[0]
    pk = run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
              "packet=pts_time,size,flags", "-of", "csv=p=0", path]).stdout.split()
    frames = []
    for line in pk:
        parts = line.split(",")
        if len(parts) >= 3 and parts[0] not in ("", "N/A"):
            frames.append((float(parts[0]), int(parts[1]), "K" in parts[2]))
    frames.sort()
    if len(frames) < 2:
        print(f"{path}: no video frames found ({st})")
        return
    dur = frames[-1][0] - frames[0][0]
    gaps = [b[0] - a[0] for a, b in zip(frames, frames[1:])]
    total = sum(f[1] for f in frames)
    keys = sum(1 for f in frames if f[2])
    # per-second frame counts show bursts and stalls
    per_sec = {}
    for t, _, _ in frames:
        per_sec[int(t - frames[0][0])] = per_sec.get(int(t - frames[0][0]), 0) + 1
    counts = [per_sec.get(i, 0) for i in range(int(dur) + 1)]
    errs = run(["ffmpeg", "-v", "warning", "-i", path, "-f", "null", "-"]).stderr.strip().splitlines()
    cc = sum(1 for e in errs if "continuity" in e.lower() or "discontinuity" in e.lower())
    print(f"{path}")
    print(f"  video      {st.get('codec_name')} {st.get('profile')} {st.get('width')}x{st.get('height')}")
    print(f"  duration   {dur:.1f}s, {len(frames)} frames, {len(frames) / dur:.1f} fps average")
    print(f"  per second {counts}")
    print(f"  bitrate    {total * 8 / dur / 1e6:.2f} Mbps (video only)")
    print(f"  keyframes  {keys}  ({dur / max(keys, 1):.1f}s apart)")
    print(f"  longest gap between frames {max(gaps) * 1000:.0f} ms")
    print(f"  decoder warnings {len(errs)} (stream jumps: {cc})")
    for e in errs[:5]:
        print(f"    {e}")


for p in sys.argv[1:]:
    analyze(p)
