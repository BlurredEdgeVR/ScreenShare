#!/usr/bin/env python3
"""Tiny adb helper: `phone.py tap "Button text"` taps the first on-screen element whose text
contains the given words (case-insensitive). `phone.py texts` lists visible texts."""
import re
import subprocess
import sys


def dump():
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    return subprocess.run(["adb", "shell", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout


def nodes(xml):
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        text, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
        yield text, (x1 + x2) // 2, (y1 + y2) // 2


cmd = sys.argv[1]
if cmd == "texts":
    for t, x, y in nodes(dump()):
        if t.strip():
            print(f"{x:5d},{y:5d}  {t}")
elif cmd == "find":
    want = sys.argv[2].lower()
    for t, x, y in nodes(dump()):
        if want in t.lower():
            print(f"{x} {y}")
            sys.exit(0)
    sys.exit(1)
elif cmd == "tap":
    want = sys.argv[2].lower()
    for t, x, y in nodes(dump()):
        if want in t.lower():
            subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
            print(f"tapped '{t}' at {x},{y}")
            sys.exit(0)
    print(f"not found: {sys.argv[2]}")
    sys.exit(1)
