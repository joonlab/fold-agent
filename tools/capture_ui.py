#!/usr/bin/env python3
"""앱 첫 화면 수집 — 앱을 열고(누르지 않음) UI 트리 + 스크린샷을 저장하고 홈으로.
사용: python3 tools/capture_ui.py <pkg> [<pkg> …]   → _data/inventory/ui/<pkg>.{txt,xml,jpg}
.txt 는 폰 에이전트가 보는 것과 같은 형식의 번호 목록.
"""
import json, os, re, subprocess, sys, time
from pathlib import Path
from xml.etree import ElementTree as ET

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
root = Path(__file__).resolve().parent.parent / "_data" / "inventory"
out = root / "ui"; out.mkdir(parents=True, exist_ok=True)
apps = {a["pkg"]: a for a in json.loads((root / "apps.json").read_text())}

def adb(*a, timeout=40):
    return subprocess.run([ADB, *a], capture_output=True, timeout=timeout)

def compact(xml: str) -> str:
    lines = []
    def walk(n, depth):
        text, desc = n.get("text", "").strip(), n.get("content-desc", "").strip()
        rid = n.get("resource-id", "").split("/")[-1]
        flags = [k for k, v in (("클릭", "clickable"), ("입력", None), ("스크롤", "scrollable")) if v and n.get(v) == "true"]
        if n.get("class", "").endswith("EditText"): flags.append("입력")
        useful = text or desc or flags
        d = depth
        if useful and n.tag == "node":
            s = "  " * min(depth, 8) + f"[{len(lines)}] {n.get('class','').split('.')[-1]}"
            if text: s += f' "{text[:80]}"'
            if desc and desc != text: s += f' 설명="{desc[:80]}"'
            if rid: s += f" id={rid}"
            if flags: s += f" [{','.join(flags)}]"
            s += f" {n.get('bounds','')}"
            lines.append(s); d = depth + 1
        for c in n:
            walk(c, d)
    try:
        walk(ET.fromstring(xml), 0)
    except ET.ParseError as e:
        return f"(XML 파싱 실패 {e})"
    return "\n".join(lines)

for pkg in sys.argv[1:]:
    a = apps.get(pkg)
    if not a:
        print(f"skip {pkg} (목록에 없음)"); continue
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    # 액티비티 이름에 $ 가 들어간 앱이 있다(YouTube Shell$HomeActivity) — 원격 셸이 변수로 먹지 않게 작은따옴표로
    adb("shell", f"am start -n '{pkg}/{a['activity']}'")
    time.sleep(float(os.environ.get("WAIT", "5")))
    top = adb("shell", "dumpsys activity activities | grep -m1 topResumedActivity").stdout.decode(errors="ignore").strip()
    adb("shell", "uiautomator", "dump", "/data/local/tmp/ui.xml")
    xml = adb("shell", "cat", "/data/local/tmp/ui.xml").stdout.decode("utf-8", errors="ignore")
    (out / f"{pkg}.xml").write_text(xml)
    txt = compact(xml)
    (out / f"{pkg}.txt").write_text(f"# {a['label']} ({pkg})\n# top: {top}\n{txt}\n")
    # 폴드는 디스플레이가 둘이라 screencap 이 경고 문구를 PNG 앞에 붙인다 → PNG 시그니처부터 자른다
    raw = adb("exec-out", "screencap", "-p").stdout
    k = raw.find(b"\x89PNG")
    png = raw[k:] if k >= 0 else raw
    tmp = out / f"{pkg}.png"; tmp.write_bytes(png)
    subprocess.run(["sips", "-s", "format", "jpeg", "-s", "formatOptions", "60", "-Z", "1100", str(tmp), "--out", str(out / f"{pkg}.jpg")], capture_output=True)
    tmp.unlink(missing_ok=True)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1)
    n = txt.count("\n") + 1 if txt else 0
    print(f"{a['label']:<16} {pkg:<45} nodes={n:<4} top={'OK' if pkg in top else top[-60:]}", flush=True)
