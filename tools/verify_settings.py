#!/usr/bin/env python3
"""설정 바로가기 실기 검증 — open_settings 가 할 일(액션으로 액티비티 시작)을 adb 로 똑같이 해 본다.
사용: python3 tools/verify_settings.py   (입력 _data/inventory/analysis.json 의 system.settings_pages)
출력: _data/inventory/settings_results.json  · pass = 오류 없이 어떤 화면이든 떴고 홈 런처가 아님
"""
import json, os, re, subprocess, time
from pathlib import Path

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
INV = Path(__file__).resolve().parent.parent / "_data" / "inventory"
pages = json.loads((INV / "analysis.json").read_text())["system"]["settings_pages"]

def adb(*a):
    return subprocess.run([ADB, *a], capture_output=True, timeout=30).stdout.decode(errors="ignore")

res = []
for p in pages:
    act = p["action"].strip()
    if "." not in act:   # 앱의 openSettings 와 같은 규칙: 점이 없을 때만 android.settings. 를 붙인다
        act = "android.settings." + act
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(0.7)
    out = adb("shell", f"am start -W -a {act}")
    time.sleep(1.5)
    top = adb("shell", "dumpsys activity activities | grep -m1 topResumedActivity")
    m = re.search(r"u0 ([\w.]+)/([\w.$]+)", top)
    top_s = f"{m.group(1)}/{m.group(2)}" if m else "?"
    if "Error" in out or "unable to resolve" in out.lower():
        v, why = "fail", "해석 불가"
    elif "launcher" in top_s:
        v, why = "fail", "홈에 머묾"
    else:
        v, why = "pass", top_s
    r = dict(p, action=act, result=v, why=why)
    res.append(r)
    print(f"{v:<5} {act:<55} {p['say'][:16]:<16} {why[:60]}", flush=True)
adb("shell", "input", "keyevent", "KEYCODE_HOME")
(INV / "settings_results.json").write_text(json.dumps(res, ensure_ascii=False, indent=1))
print(f"pass {sum(r['result']=='pass' for r in res)} / {len(res)}")
