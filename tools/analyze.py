#!/usr/bin/env python3
"""폴드 에이전트 실행 기록 분석 → 지식 노트 개정의 근거가 되는 보고서.

사용: python3 tools/analyze.py <폰에서 당겨온 폴더>   (보통 ./dev.sh analyze)
출력: 표준출력 + <폴더>/../reports/report-<시각>.md

보는 것
  - 실행별: 요청(음성 후보) · 상태 · 단계 · 시간 · 토큰 · 도구 경로 · 경고 · 학습 · 확인 · 평가
  - 모아서: 성공률 · 자주 하는 요청 · 자주 쓰는 앱 · 막힌 동작(화면 변화 없음) · 앱 이름 실패
  - 실패한 실행은 마지막 화면 앞부분까지 — 원인 판정은 추측 말고 이걸 읽고 한다
"""
import json
import sys
import time
from collections import Counter
from pathlib import Path


def load(root: Path):
    runs = []
    for f in sorted(root.glob("runs/*/*.jsonl")):
        ev = []
        for line in f.read_text(encoding="utf-8").splitlines():
            try:
                ev.append(json.loads(line))
            except json.JSONDecodeError:
                pass
        if ev:
            runs.append((f.stem, ev))
    return runs


def summarize(run_id, ev):
    r = {"id": run_id, "tools": [], "guards": [], "learns": [], "confirms": [], "errors": [],
         "llm_ms": [], "tokens": 0, "cached": 0, "feedback": None, "status": "incomplete",
         "last_screen": "", "no_change": []}
    for e in ev:
        t, d = e["type"], e["data"]
        if t == "run_start":
            r["request"] = d.get("request", "")
            r["source"] = d.get("source", "?")
            r["stt"] = d.get("stt")
            r["model"] = d.get("model")
            r["build"] = d.get("build")
            r["start_pkg"] = d.get("foreground")
            r["recipes_used"] = d.get("recipes", [])
        elif t == "llm":
            r["llm_ms"].append(d.get("ms", 0))
            u = d.get("usage") or {}
            r["tokens"] += u.get("total_tokens", 0) or 0
            r["cached"] += (u.get("prompt_tokens_details") or {}).get("cached_tokens", 0) or 0
        elif t == "tool":
            r["tools"].append(d)
            if d.get("changed") is False and d.get("name") != "wait":
                r["no_change"].append((d.get("pkg"), d.get("name"), d.get("outcome", "").split("\n")[0][:60]))
        elif t == "msg" and d.get("role") == "tool" and "[현재 화면]" in (d.get("content") or ""):
            r["last_screen"] = d["content"].split("[현재 화면]", 1)[1].strip()
        elif t == "guard":
            r["guards"].append(d)
        elif t == "learn":
            r["learns"].append(d)
        elif t == "confirm":
            r["confirms"].append(d)
        elif t == "error":
            r["errors"].append(d.get("message", ""))
        elif t == "run_end":
            r.update(status=d.get("status"), message=d.get("message"), ms=d.get("ms"), steps=d.get("steps"))
        elif t == "feedback":
            r["feedback"] = d.get("good")
    return r


def path_str(r):
    out = []
    for d in r["tools"]:
        a = d.get("args", {})
        n = d.get("name")
        if n == "open_app":
            out.append(f"open_app({a.get('name')})")
        elif n in ("tap", "long_press"):
            lab = d.get("outcome", "").split(": ", 1)[-1].split("\n")[0][:24]
            out.append(f"{n}「{lab}」")
        elif n == "type_text":
            out.append(f"type「{a.get('text')}」" + ("⏎" if a.get("submit") else ""))
        else:
            out.append(f"{n}({','.join(str(v) for v in a.values())[:20]})")
    return " → ".join(out)


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "_data/phone")
    runs = [summarize(i, e) for i, e in load(root)]
    # recipes.jsonl 의 bad 표시(폰의 👎 또는 맥에서 수동 제외)도 부정 평가로 친다
    bad = set()
    rf = root / "recipes.jsonl"
    if rf.exists():
        for line in rf.read_text(encoding="utf-8").splitlines():
            try:
                j = json.loads(line)
            except json.JSONDecodeError:
                continue
            if j.get("bad"):
                bad.add(j.get("run"))
    for r in runs:
        if r["feedback"] is None and r["id"] in bad:
            r["feedback"] = False
    L = []
    p = L.append
    p(f"# 폴드 에이전트 실행 분석 — {time.strftime('%Y-%m-%d %H:%M')}")
    p(f"\n기록 {len(runs)}건 · 폴더 `{root}`\n")
    if not runs:
        print("\n".join(L)); return

    st = Counter(r["status"] for r in runs)
    ok = st.get("ok", 0)
    fb = [r["feedback"] for r in runs if r["feedback"] is not None]
    p("## 요약\n")
    p(f"- 상태: " + " · ".join(f"{k} {v}" for k, v in st.most_common()))
    p(f"- 성공률(ok/전체): {ok}/{len(runs)} = {ok / len(runs):.0%}"
      + (f" · 사용자 평가 👍{sum(fb)} 👎{len(fb) - sum(fb)}" if fb else " · 사용자 평가 없음"))
    steps = [r.get("steps") or 0 for r in runs if r["status"] == "ok"]
    ms = [r.get("ms") or 0 for r in runs if r["status"] == "ok"]
    llm = [m for r in runs for m in r["llm_ms"]]
    if steps:
        p(f"- 성공 실행 평균: {sum(steps) / len(steps):.1f}단계 · {sum(ms) / len(ms) / 1000:.1f}초")
    if llm:
        p(f"- LLM 호출 {len(llm)}회 · 평균 {sum(llm) / len(llm) / 1000:.1f}초 · 최대 {max(llm) / 1000:.1f}초")
    tok = sum(r["tokens"] for r in runs); cached = sum(r["cached"] for r in runs)
    p(f"- 토큰 합계 {tok:,} (캐시 적중 {cached:,})")

    p("\n## 자주 쓰는 앱\n")
    apps = Counter(d.get("pkg") for r in runs for d in r["tools"] if d.get("pkg"))
    p(" · ".join(f"`{k}` {v}" for k, v in apps.most_common(12)) or "-")

    nc = Counter((pkg, name, out) for r in runs for pkg, name, out in r["no_change"])
    p("\n## 막힌 동작(화면 변화 없음) — 앱별 요령 후보\n")
    if nc:
        for (pkg, name, out), c in nc.most_common(15):
            p(f"- {c}회 · `{pkg}` · {name} · {out}")
    else:
        p("- 없음")

    fails = [(d.get("args", {}).get("name"), r["id"]) for r in runs for d in r["tools"]
             if d.get("name") == "open_app" and "못 찾음" in d.get("outcome", "")]
    learns = [(l, r["id"]) for r in runs for l in r["learns"]]
    p("\n## 앱 이름 실패 · 자동 학습\n")
    for n, rid in fails:
        p(f"- open_app 실패: 「{n}」 ({rid})")
    for l, rid in learns:
        p(f"- 학습: {json.dumps(l, ensure_ascii=False)} ({rid})")
    if not fails and not learns:
        p("- 없음")

    p("\n## 자주 하는 요청(같은 앞 12글자 묶음)\n")
    reqs = Counter((r.get("request") or "")[:12] for r in runs)
    for k, v in reqs.most_common(10):
        p(f"- {v}회 · {k}…")

    p("\n## 실행별\n")
    for r in runs:
        mark = {"ok": "✅", "failed": "❌", "stuck": "🔁", "limit": "⏱", "cancel": "⏹", "error": "💥"}.get(r["status"], "?")
        fbm = "" if r["feedback"] is None else (" 👍" if r["feedback"] else " 👎")
        p(f"### {mark} {r['id']}{fbm} — 「{r.get('request', '')}」")
        stt = r.get("stt") or {}
        alts = stt.get("alternatives") or []
        p(f"- 출처 {r.get('source')} · 모델 {r.get('model')} · build {r.get('build')} · "
          f"{r.get('steps', '?')}단계 · {(r.get('ms') or 0) / 1000:.1f}초 · 토큰 {r['tokens']:,}")
        if len(alts) > 1:
            p(f"- 음성 후보: " + " / ".join(f"「{a}」" for a in alts[:5]) + f" (온디바이스 {stt.get('onDevice')})")
        if r.get("recipes_used"):
            p(f"- 참고한 과거 경로: {', '.join(r['recipes_used'])}")
        p(f"- 경로: {path_str(r) or '-'}")
        p(f"- 결과: {r.get('message', '(종료 기록 없음)')}")
        for g in r["guards"]:
            p(f"- 🔁 반복 경고: {g.get('key')} ×{g.get('count')}")
        for c in r["confirms"]:
            p(f"- ❓ 확인: 「{c.get('label')}」 → {'실행' if c.get('ok') else '거부'}")
        for e in r["errors"]:
            p(f"- 💥 {e[:200]}")
        if r["status"] != "ok" and r["last_screen"]:
            p("- 마지막 화면 앞부분:\n```\n" + "\n".join(r["last_screen"].splitlines()[:25]) + "\n```")
        p("")

    text = "\n".join(L)
    out = root.parent / "reports"
    out.mkdir(parents=True, exist_ok=True)
    f = out / f"report-{time.strftime('%Y%m%d-%H%M%S')}.md"
    f.write_text(text, encoding="utf-8")
    print(text)
    print(f"\n(저장: {f})")


if __name__ == "__main__":
    main()
