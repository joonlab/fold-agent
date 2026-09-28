#!/usr/bin/env python3
"""지금 폰 화면을 번호 목록(좌표 포함)으로 — 탐색용. 사용: python3 tools/ui.py [grep 정규식]"""
import os, re, subprocess, sys
from xml.etree import ElementTree as ET
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
subprocess.run([ADB, "shell", "uiautomator", "dump", "/data/local/tmp/x.xml"], capture_output=True)
xml = subprocess.run([ADB, "shell", "cat", "/data/local/tmp/x.xml"], capture_output=True).stdout.decode("utf-8", "ignore")
pat = re.compile(sys.argv[1]) if len(sys.argv) > 1 else None
i = 0
for n in ET.fromstring(xml).iter("node"):
    t, d = n.get("text", "").strip(), n.get("content-desc", "").strip()
    rid = n.get("resource-id", "").split("/")[-1]
    c = n.get("clickable") == "true"
    if t or d or c:
        line = f'[{i}] {n.get("class","").split(".")[-1]} "{t[:50]}" d="{d[:50]}" id={rid} {"C" if c else ""} {n.get("bounds")}'
        if not pat or pat.search(line):
            print(line)
        i += 1
