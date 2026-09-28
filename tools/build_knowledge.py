#!/usr/bin/env python3
"""분석(analysis.json) + 실기 검증(link_results.json · settings_results.json) → 폰 에이전트 지식 파일.

사용: python3 tools/build_knowledge.py
출력(정본, git):
  knowledge/knowledge.md   — 매 호출 시스템 프롬프트에 들어간다. 짧게: 사용자·시스템 바로가기·앱 색인·음성 힌트
  knowledge/apps/<pkg>.md  — 그 앱에 들어갈 때만 주입: 화면 구조·하는 법·딥링크·주의
  knowledge/aliases.json   — 사용자 호칭 → 패키지 (open_app 이 먼저 본다)
딥링크 표기: ✅ 실기 통과 · 🟡 앱은 열림(검색어 반영 미확인) · 📐 형식만(ID 필요·미검증) · 실패는 뺀다.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INV = ROOT / "_data" / "inventory"
OUT = ROOT / "knowledge"
(OUT / "apps").mkdir(parents=True, exist_ok=True)

analysis = json.loads((INV / "analysis.json").read_text())
links = json.loads((INV / "link_results.json").read_text()) if (INV / "link_results.json").exists() else []
settings_res = json.loads((INV / "settings_results.json").read_text()) if (INV / "settings_results.json").exists() else []
inventory = {a["pkg"]: a for a in json.loads((INV / "inventory.json").read_text())}

# 검증을 일부러 건너뛴 표준 형식(열면 작성 화면까지만 — 발송은 확인 게이트가 막는다)
STANDARD_OK = re.compile(r"^(sms|smsto|mailto|tel):")
NEVER = re.compile(r"google\.navigation|zoom|meet\.google|chatgpt\.com/\?q|tg://msg|twitter://post|barcelona://create|open\.kakao\.com", re.I)

verdict = {(l["pkg"], l["uri"]): l for l in links}


def link_mark(pkg, dl):
    uri = dl["uri"].strip()
    if NEVER.search(uri):
        return None
    if STANDARD_OK.match(uri):
        return "📐", "표준 형식 — 작성 화면만 열림, 발송은 확인 필요"
    v = verdict.get((pkg, uri))
    if v is None:
        return None  # 액션형 등 — 시스템 절에서 다룬다
    if v["result"] == "pass":
        return "✅", v["why"]
    if v["result"] == "partial":
        return "🟡", v["why"]
    if v["result"] == "skip" and dl["confidence"] == "high":
        return "📐", "형식만(ID 필요·미검증)"
    return None


def clip(s, n):
    s = " ".join(s.split())
    return s if len(s) <= n else s[:n] + "…"


def fix_uri(s):
    # 분석 에이전트가 appname 을 잘못 적은 경우(com.foldagent 등) — 네이버 개발 가이드상 앱 식별자면 되므로 우리 패키지로 통일
    return re.sub(r"appname=[\w.]+", "appname=kr.joonlab.foldagent", s)


FINANCE_CAUTION = ("금융·인증 앱도 대개 화면을 읽을 수 있다(토스 확인). 화면이 비어 보이면 먼저 wait 로 로딩을 기다리고 다시 본다 — "
                   "「이동했어요」·로딩 화면은 보안 차단이 아니다. 기다려도 비어 있고 look 도 검게 나올 때만 보안 화면으로 보고 finish 로 넘긴다. "
                   "「원격제어/접근성 앱 실행 중」 경고로 앱이 닫히면 그대로 알린다")


def fix_caution(c):
    # 분석 에이전트가 쓴 「금융 앱은 트리가 비면 즉시 finish」 문구가 로딩 중 빈 웹뷰를 보안 차단으로 오판하게 만들었다(2026-09-26 토스)
    # — 분석을 새로 돌려도 이 교정이 유지되게 빌더에서 바꿔 끼운다
    return FINANCE_CAUTION if c.startswith("금융") and "토스 확인" not in c else c


def norm(s):
    return re.sub(r"\s+", "", s.lower())


aliases = {}
voice = []
cheat = []   # knowledge.md 에 직접 넣는 검증된 딥링크 — 앱을 열기 전 첫 판단에 써야 하므로 앱별 파일에만 두면 안 된다
index_rows = []
app_files = 0
for a in analysis["apps"]:
    pkg = a["pkg"]
    marks = []
    for dl in a["deeplinks"]:
        m = link_mark(pkg, dl)
        if m:
            extra = " (package 지정 필요)" if verdict.get((pkg, dl["uri"].strip()), {}).get("needs_package") else ""
            if m[0] == "✅" and ("{" in dl["uri"] or "검색" in dl["task"]):
                cheat.append((inventory.get(pkg, {}).get("usage", {}).get("yearly", {}).get("sec", 0),
                              f"- {a['label']} {dl['task']}: open_link(\"{fix_uri(dl['uri'])}\"" + (f", package=\"{pkg}\"" if extra else "") + ")"))
            marks.append(f"- {m[0]} {dl['task']}: `open_link(\"{dl['uri']}\"{', package=' + chr(34) + pkg + chr(34) if extra else ''})`{extra}")
    L = [f"# {a['label']} ({pkg})"]
    ov = OUT / "overrides" / f"{pkg}.md"   # 사람이 폰에서 실측한 절차 — 분석 결과보다 우선, 재구축해도 유지
    if ov.exists():
        L.append(ov.read_text(encoding="utf-8").strip())
    if a["aliases"]:
        L.append("부르는 이름: " + ", ".join(a["aliases"][:8]))
    L.append(f"용도: {a['purpose']}")
    L.append(f"열면: {a['captured_screen']}")
    L.append("화면:\n" + a["screen_structure"].strip())
    if a["how_to"]:
        # 「상품 검색: open_app(쿠팡) → 검색창…」 같은 화면 절차를 보고 모델이 딥링크 앞에 open_app 을 붙였다(2026-09-26 X1·E4·E6)
        if any(x.startswith("- ✅") and "{" in x for x in marks):   # 검색처럼 값이 들어가는 딥링크가 있을 때만(배민은 「앱 홈」뿐)
            L.append("★ 아래 딥링크(✅)가 있는 일은 open_link 한 번으로(open_app 을 먼저 부르지 않는다). 「하는 법」의 화면 절차는 딥링크가 안 될 때만")
        L.append("하는 법:")
        L += [f"- {h['task']}: {h['steps']}" + (" (일반 지식)" if h["basis"] == "일반 지식" else "") for h in a["how_to"][:6]]
    if marks:
        L.append("딥링크({query} 자리에 검색어):")
        L += marks
    if a["tree_quality"] != "good":
        L.append(f"접근성 트리: {a['tree_quality']} — 항목이 안 보이면 딥링크를 쓰거나 finish(success=false)로 사용자에게 넘긴다")
    if a["cautions"]:
        L.append("주의:")
        L += [f"- {c}" for c in a["cautions"][:6]]
    body = "\n".join(L) + "\n"
    # 수집 과정 메모(스크린샷 미수집 등)는 폰 에이전트에게 의미가 없다 — 걸러낸다
    body = re.sub(r"\s*\([^()\n]*(스크린샷|\.jpg)[^()\n]*\)", "", body)
    body = re.sub(r"[.·,]?\s*스크린샷[^.\n]*(판정|있음|없음|미수집)[^.\n]*", "", body)
    body = "\n".join(l for l in body.splitlines() if not l.startswith("- 스크린샷") and l.strip() not in ("-", "") and not re.search(r"스크린샷(\(\.jpg\))?\s*(파일)?\s*(없음|미수집)|\.jpg\)?\s*미수집", l)) + "\n"
    (OUT / "apps" / f"{pkg}.md").write_text(body, encoding="utf-8")
    app_files += 1

    for al in [a["label"], *a["aliases"]]:
        k = norm(al)
        if k and k not in aliases:
            aliases[k] = pkg
    voice += a["voice_terms"]
    usage = inventory.get(pkg, {}).get("usage", {}).get("yearly", {}).get("sec", 0)
    ok_links = sum(1 for x in marks if x.startswith("- ✅"))
    index_rows.append((usage, f"{a['label']}({'·'.join(a['aliases'][:3])})"
                              + (f" 딥링크✅{ok_links}" if ok_links else "")
                              + (" ⚠트리빈약" if a["tree_quality"] == "poor" else "")))

# 분석 대상 밖(금융 앱 등)이라도 사람이 실측한 절차가 있으면 요령 파일을 만든다
analyzed = {a["pkg"] for a in analysis["apps"]}
for ov in sorted((OUT / "overrides").glob("*.md")):
    if ov.stem not in analyzed:
        lab = inventory.get(ov.stem, {}).get("label", ov.stem)
        (OUT / "apps" / f"{ov.stem}.md").write_text(f"# {lab} ({ov.stem})\n" + ov.read_text(encoding="utf-8").strip() + "\n", encoding="utf-8")
        app_files += 1

(OUT / "aliases.json").write_text(json.dumps(aliases, ensure_ascii=False, indent=1), encoding="utf-8")

sysd = analysis["system"]
ok_settings = [s for s in settings_res if s.get("result") == "pass"]
settings_line = " · ".join(f"{s['say']}={s['action'].replace('android.settings.', '')}" for s in ok_settings) \
    if ok_settings else "(검증 전)"
index_rows.sort(key=lambda x: -x[0])

base_notes = (ROOT / "knowledge" / "knowledge.md").read_text(encoding="utf-8") if (ROOT / "knowledge" / "knowledge.md").exists() else ""
user_sec = ""
m = re.search(r"## 사용자\n(.*?)(?=\n## )", base_notes, re.S)
if m:
    user_sec = m.group(1).strip()

# 음성 힌트: 기존 + 앱 호칭 + 앱이 준 고유명사 (중복 제거, 100개 이내)
old_terms = []
m2 = re.search(r"## 음성 인식 힌트\n(.*?)(?=\n## |\Z)", base_notes, re.S)
if m2:
    old_terms = [t.strip() for line in m2.group(1).splitlines() if line.strip().startswith("-")
                 for t in line.strip("- ").split(",")]
terms = []
# 한글 호칭·고유명사가 인식기에 더 필요하다 — 영문 라벨은 뒤로
kor = [al for a in analysis["apps"] for al in a["aliases"] if re.search("[가-힣]", al)]
for t in old_terms + kor + voice + [a["label"] for a in analysis["apps"]]:
    t = t.strip()
    if t and t not in terms and len(t) <= 20:
        terms.append(t)
terms = terms[:90]

K = [
    "# 폴드 에이전트 — 이 폰에 대해 아는 것",
    "(맥의 Claude Code 가 수집·실기 검증해 만든 노트. 앱별 상세 요령은 그 앱에 들어갈 때 [앱 요령]으로 따로 온다.)",
    "",
    "## 사용자",
    user_sec or "- 한국어로 말한다.",
    "",
    "## 기기",
    "- Galaxy Z Fold8(SM-F971N) · Android 17 · One UI 9.0. 접힌 상태(커버 화면)로 쓰는 일이 많다 — 화면이 좁아 목록이 잘릴 수 있으니 scroll 로 확인.",
] + [f"- {clip(f, 150)}" for f in sysd["oneui_facts"] if "스와이프 제스처가 없어" not in f][:5] + [
    # 개인 실측 메모에서 옮김(2026-09-28)
    "- 메인 화면 배경·잠금화면·루틴의 「(메인 화면)」 동작은 접힌 상태에선 회색(「커버 화면에서는 추가할 수 없어요」) — 펼쳐 달라고 말하고 멈춘다. 배경화면 및 스타일도 지금 켜진 화면만 바꾼다",
    "- 커버 화면과 메인 화면의 홈 배치는 따로다 — 한쪽에 둔 앱·폴더는 다른 쪽에 안 생긴다. 어느 쪽인지는 [현재 화면]의 접힘/펼침으로",
] + [
    "",
    "## 바로가기 (화면을 누르기 전에 먼저 고려)",
    "- 설정 화면 open_settings: " + settings_line,
] + [f"- {i['task']}: {clip(fix_uri(i['how']), 170)}" for i in sysd["intents"]
       if i["confidence"] != "low" and not NEVER.search(i["how"])][:14] + [
    "",
    "## 검증된 딥링크 (✅ 폰에서 실제로 열어 검색어 반영까지 확인 — 앱 화면을 누르기 전에 먼저 쓴다. {query} 는 URL 인코딩)",
] + [c[1] for c in sorted(cheat, key=lambda x: -x[0])] + [
    "",
    "## 실측 절차가 있는 앱 (그 앱의 [앱 요령] 맨 위 ★ 절차를 따른다)",
] + [f"- {inventory.get(o.stem, {}).get('label', o.stem)}: {clip(o.read_text(encoding='utf-8').splitlines()[0].lstrip('★ '), 120)}"
     for o in sorted((OUT / "overrides").glob("*.md"))] + [
    "- TMAP 경로(출발지 지정·옵션): geocode(출발지)·geocode(도착지) → tmap://route?startname=…&startx=…&starty=…&goalname=…&goalx=…&goaly=… (좌표 없으면 경로 못 잡음)",
    "",
    "## 공통 주의",
] + [f"- {clip(fix_caution(c), 240)}" for c in sysd["general_cautions"][:9]] + [
    "",
    "## 자주 쓰는 앱 (사용량 순 · 부르는 이름 · 검증된 딥링크 수)",
    " / ".join(r[1] for r in index_rows[:35]) + " (나머지는 「설치된 앱」 목록)",
    "",
    "## 음성 인식 힌트",
    "- " + ", ".join(terms),
    "",
]
# 도구가 생기며 낡은 요령 — 분석(analysis.json)은 도구가 없던 때 만들어져 「화면으로 하라」고 적었다(2026-09-26 도구 추가).
# 재구축해도 되살아나지 않게 여기서 바꾼다. None = 줄을 뺀다
SUPERSEDED = [
    (r"^- 기기 SM-F971N\(폴드8\)은 지금 접힌 상태", "- 접힘/펼침과 해상도는 [현재 화면] 머리말에 매번 나온다(커버 1248x1972 · 메인 2448x1848). 좌표·레이아웃은 그 상태 기준"),
    (r"^- 퀵 설정에 손전등", "- 손전등·음량·밝기·소리 모드·방해금지는 전용 도구로. 블루투스·자동 회전·NFC·절전·다크 모드 = quick_toggle. 와이파이·비행기 모드·모바일 데이터는 끄지 않는다(비서 연결이 끊긴다). 설정값 읽기·충전 중 화면 켜짐·화면 꺼짐 시간 = system_setting(설정 화면에 안 보여도 값은 있다) · 자동 회전 타일이 없거나 실패하면 system_setting"),
    # 분석의 「백그라운드 시작 제한 — 실행 후 wait」는 이 앱(접근성 서비스)에서 한 번도 재현 안 됐고, 링크마다 wait 를 붙이게 했다(2026-09-26)
    (r"^- Android 백그라운드 액티비티 시작 제한", "- open_link·open_app 은 에이전트가 뒤에 있어도 앱을 연다(도구가 앱이 뜰 때까지 기다린다). 결과의 [현재 화면] 앱이 다를 때만 다시 시도"),
    (r"^- 스톱워치:", "- 스톱워치: stopwatch(start | stop | reset | lap | get) — 시계 앱을 알아서 조작"),
    (r"^- 와이파이·블루투스·위치·NFC는 일반 앱이 직접 켤 수 없다", "- 와이파이·위치는 일반 앱이 직접 못 켠다 — 설정/패널을 열고 토글을 tap(와이파이 끄기는 하지 않는다)"),
    (r"^- 알람 목록 보기:", "- 알람 목록 보기: open_screen(alarms) · 타이머 목록: open_screen(timers)"),
    (r"^- 캘린더 일정 추가 화면:", "- 캘린더 일정 추가 화면: open_screen(new_event, title, begin, end) — 제목·시간이 채워진 작성 화면, 저장은 사용자 확인 후"),
    (r"^- 카메라 열기:", "- 카메라: open_screen(photo_camera | video_camera | qr_scan) — 촬영·녹화 버튼은 사용자가 원할 때만"),
]
# 줄 안의 일부만 바꿀 것
SUPERSEDED_PART = [
    ("와이파이 켜줘 / 꺼줘=panel.action.WIFI", "와이파이 켜줘(끄기는 안 함)=panel.action.WIFI"),
    # WALLPAPER_SETTINGS 는 「열린다」까지만 검증됐다 — 삼성 화면이 아니라 AOSP 선택기(Choose wallpaper from)를 연다(개인 실측 메모 함정 6, 2026-09-28)
    ("배경화면 바꾸기=WALLPAPER_SETTINGS", "배경화면=WALLPAPER_SETTINGS 쓰지 않음(AOSP 선택기) — 사진은 갤러리 그 사진→옵션 더보기→배경화면으로 설정, 동영상·스타일은 설정 앱→배경화면 및 스타일"),
]
for old, new in SUPERSEDED_PART:
    hit = [i for i, line in enumerate(K) if old in line]
    if not hit:
        print(f"⚠️ 대체할 부분 없음: {old}")
    for i in hit:
        K[i] = K[i].replace(old, new)
for pat, rep in SUPERSEDED:
    hit = [i for i, line in enumerate(K) if re.search(pat, line)]
    if not hit:
        print(f"⚠️ 대체할 줄 없음(분석이 바뀌었나): {pat}")
    for i in reversed(hit):
        if rep is None: del K[i]
        else: K[i] = rep
(OUT / "knowledge.md").write_text("\n".join(K), encoding="utf-8")
print(f"apps/ {app_files}개 · aliases {len(aliases)} · knowledge.md {len(chr(10).join(K))}자 · 설정 바로가기 {len(ok_settings)} · 음성 힌트 {len(terms)}")
