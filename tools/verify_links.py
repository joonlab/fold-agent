#!/usr/bin/env python3
"""딥링크 실기 검증 — 실제로 열어 보고 (1) 예상 앱이 떴는지 (2) 검색어가 화면에 반영됐는지 본다.
사용: python3 tools/verify_links.py <후보.json> <결과.json>
후보: [{"pkg": 기대 패키지, "uri": "…{query}…", "package": 강제 패키지(선택), "task": …}]
자리표시자 {query}류는 TEST_QUERY 로 채운다. 채울 수 없는 자리표시자가 남으면 검증하지 않는다(skip).
⛔ 결제·주문·발송·통화로 이어질 수 있는 링크는 호출하는 쪽에서 미리 빼고 넘길 것.
"""
import json, os, re, subprocess, sys, time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
TEST_QUERY = "강남역"
FILL = {k: TEST_QUERY for k in ("query", "q", "keyword", "search", "name", "place", "destination", "dest", "text", "term", "location", "address", "goal")}

def adb(*a, t=30):
    return subprocess.run([ADB, *a], capture_output=True, timeout=t)

def fill(uri):
    def rep(m):
        k = m.group(1).lower()
        return FILL.get(k, m.group(0))
    u = re.sub(r"\{([A-Za-z_]+)\}", rep, uri)
    return u, re.search(r"\{[A-Za-z_]+\}", u) is None

cands = json.load(open(sys.argv[1]))
res = []
for c in cands:
    uri, ok_fill = fill(c["uri"])
    r = dict(c, tested_uri=uri)
    if not ok_fill:
        r.update(result="skip", why="채울 수 없는 자리표시자"); res.append(r); continue
    def attempt(pkg_forced):
        adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(0.8)
        cmd = f"am start -W -a android.intent.action.VIEW -d '{uri}'" + (f" -p {pkg_forced}" if pkg_forced else "")
        out = adb("shell", cmd).stdout.decode(errors="ignore")
        time.sleep(4)
        top = adb("shell", "dumpsys activity activities | grep -m1 topResumedActivity").stdout.decode(errors="ignore")
        adb("shell", "uiautomator", "dump", "/data/local/tmp/v.xml")
        xml = adb("shell", "cat", "/data/local/tmp/v.xml").stdout.decode("utf-8", errors="ignore")
        m = re.search(r"u0 ([\w.]+)/", top)
        top_pkg = m.group(1) if m else "?"
        has_q = TEST_QUERY in xml
        if "Error" in out or "unable to resolve" in out.lower():
            return "fail", "해석 불가: " + (out.strip().splitlines() or [""])[-1][:120], top_pkg
        if top_pkg != c["pkg"]:
            return "fail", f"다른 앱이 뜸: {top_pkg}", top_pkg
        if "{" in c["uri"] and not has_q:
            return "partial", "앱은 떴지만 검색어가 화면에 없음", top_pkg
        return "pass", ("검색어 반영" if has_q else "앱 열림"), top_pkg

    verdict, why, top_pkg = attempt("")
    r["needs_package"] = False
    if verdict == "fail" and c.get("forced"):
        v2, w2, t2 = attempt(c["forced"])
        if v2 != "fail":
            verdict, why, top_pkg = v2, w2 + " (package 지정 필요)", t2
            r["needs_package"] = True
    r.update(result=verdict, why=why, top=top_pkg)
    print(f"{verdict:<7} {c['pkg']:<40} {uri[:70]}  — {why}", flush=True)
    res.append(r)
adb("shell", "input", "keyevent", "KEYCODE_HOME")
json.dump(res, open(sys.argv[2], "w"), ensure_ascii=False, indent=1)
print(f"pass {sum(r['result']=='pass' for r in res)} / partial {sum(r['result']=='partial' for r in res)} / fail {sum(r['result']=='fail' for r in res)} / skip {sum(r['result']=='skip' for r in res)}")
