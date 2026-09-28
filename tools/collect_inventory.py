#!/usr/bin/env python3
"""폰 앱 인벤토리 수집(맥에서 adb) — 사용량 · 딥링크 스킴/호스트 · 주요 인텐트 액션.
사용: python3 tools/collect_inventory.py   → _data/inventory/inventory.json
전제: _data/inventory/apps.json (앱에서 --ez exportapps true 로 내보낸 라벨 목록)
"""
import json, re, subprocess, os
from pathlib import Path

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
root = Path(__file__).resolve().parent.parent / "_data" / "inventory"
apps = json.loads((root / "apps.json").read_text())

def sh(cmd):
    return subprocess.run([ADB, "shell", cmd], capture_output=True, text=True, timeout=60).stdout

def hms(s):  # "1:14:28" | "21:16" → 초
    parts = [int(p) for p in s.split(":")]
    while len(parts) < 3: parts.insert(0, 0)
    return parts[0] * 3600 + parts[1] * 60 + parts[2]

usage_txt = sh("dumpsys usagestats")
def section(name):
    i = usage_txt.find(f"In-memory {name} stats")
    if i < 0: return ""
    j = usage_txt.find("In-memory", i + 10)
    return usage_txt[i:j if j > 0 else None]
usage = {}
for per in ("yearly", "monthly", "weekly"):
    for m in re.finditer(r'package=(\S+) totalTimeUsed="([^"]+)" lastTimeUsed="([^"]+)".*?appLaunchCount=(\d+)', section(per)):
        pkg, tot, last, cnt = m.group(1), hms(m.group(2)), m.group(3), int(m.group(4))
        u = usage.setdefault(pkg, {})
        u[per] = {"sec": u.get(per, {}).get("sec", 0) + tot, "launches": u.get(per, {}).get("launches", 0) + cnt}
        if not last.startswith("1970"):
            u["last"] = max(u.get("last", ""), last)

out = []
for a in apps:
    pkg = a["pkg"]
    dump = sh(f"dumpsys package {pkg}")
    # 딥링크: Activity Resolver Table 의 Schemes/Authorities 절
    schemes = sorted(set(re.findall(r'^\s+Scheme: "([^"]+)"', dump, re.M)))
    hosts = sorted(set(re.findall(r'^\s+Authority: "([^"]+)"', dump, re.M)))[:40]
    actions = sorted(set(a2 for a2 in re.findall(r'^\s+Action: "([^"]+)"', dump, re.M)
                         if not a2.startswith("android.intent.action.MAIN")))[:60]
    perms = sorted(set(re.findall(r'^\s+(android\.permission\.[A-Z_]+): granted=true', dump, re.M)))
    a2 = dict(a)
    a2.update(usage=usage.get(pkg, {}), schemes=schemes, hosts=hosts, actions=actions, granted=perms)
    out.append(a2)

out.sort(key=lambda x: -(x["usage"].get("yearly", {}).get("sec", 0)))
(root / "inventory.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
print(f"{len(out)} apps → {root/'inventory.json'}")
for x in out[:40]:
    y = x["usage"].get("yearly", {})
    print(f"{y.get('sec',0)//60:6d}분 {y.get('launches',0):5d}회  {x['label']:<18} {x['pkg']:<45} schemes={','.join(x['schemes'][:6])}")
