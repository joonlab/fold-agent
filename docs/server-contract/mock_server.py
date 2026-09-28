#!/usr/bin/env python3
"""
폴드 에이전트 홈 서버 계약(/api/phone)의 최소 목(mock) 서버.

- 표준 라이브러리만 쓴다. 응답은 전부 가짜 데이터다(실제 메일·일정·스킬을 건드리지 않는다).
- 계약서: 같은 폴더의 CONTRACT.md. 폰 앱이 읽는 필드 모양만 맞춰 두었다.
- 실제 서버는 공개하지 않았다(개인 인프라에 묶여 있다). 직접 만들 때 이 파일을 뼈대로 쓰면 된다.

실행:
    MOCK_TOKEN=dev-token python3 mock_server.py --port 8787
    curl -s -H 'Authorization: Bearer dev-token' http://127.0.0.1:8787/api/phone/health

폰 앱은 https 주소만 받는다. 폰에서 붙이려면 이 서버 앞에 TLS 를 붙여 주는 프록시
(예: 사설망의 `tailscale serve`, 또는 로컬 인증서를 쓰는 리버스 프록시)를 둔다.
"""
import argparse
import hashlib
import hmac
import json
import os
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

TOKEN = os.environ.get("MOCK_TOKEN", "dev-token")
PREFIX = "/api/phone"

READ_OPS = {
    "agenda", "brief_latest", "mail_triage", "mail_awaiting", "mail_search", "mail_read",
    "memory_search", "memory_get", "skill_search", "skill_view", "mcp_tools",
    "job_status",
}
WRITE_OPS = {"memory_add", "event_create", "event_delete", "mail_send", "mail_reply"}
GATED_BY_POLICY = {"run", "mcp_call"}          # 서버가 need_confirm 으로 확인을 요구할 수 있는 op
OTHER_OPS = {"code_run", "job_cancel", "claude_task", "job_answer"}
ALL_OPS = READ_OPS | WRITE_OPS | GATED_BY_POLICY | OTHER_OPS

LOCK = threading.Lock()
DONE_CONFIRM = {}      # confirmId -> 첫 결과(24시간 멱등을 흉내)
JOBS = {}              # jobId -> job dict
UPLOADS = {}           # fileId -> {name, mime, size, data}
FILES = {}             # fileId -> (name, mime, bytes)   결과 첨부
CURSOR = [0]


def now_ms():
    return int(time.time() * 1000)


def ok(data):
    return 200, {"ok": True, "data": data}


def fail(status, code, message):
    return status, {"ok": False, "error": {"code": code, "message": message}}


def confirm_token(op, args):
    body = json.dumps({"op": op, "args": args}, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(body.encode()).hexdigest()


def bump(job):
    CURSOR[0] += 1
    job["updatedAt"] = now_ms()
    job["_seq"] = CURSOR[0]


def add_file(name, mime, data):
    fid = "f_" + uuid.uuid4().hex[:12]
    FILES[fid] = (name, mime, data)
    return {"fileId": fid, "name": name, "mime": mime, "size": len(data)}


# ─────────────────────────── 가짜 데이터 ───────────────────────────

def fake_agenda(args):
    day = args.get("from") or time.strftime("%Y-%m-%d")
    return {"events": [
        {"id": "ev_1", "calendarId": "cal_work", "calendar": "업무", "summary": "주간 회의",
         "start": f"{day}T10:00:00+09:00", "end": f"{day}T11:00:00+09:00", "allDay": False},
        {"id": "ev_2", "calendarId": "cal_home", "calendar": "개인", "summary": "장보기",
         "start": f"{day}T18:30:00+09:00", "end": f"{day}T19:00:00+09:00", "allDay": False,
         "note": "우유, 달걀, 사과"},
    ]}


def fake_mail_items():
    return {"items": [
        {"id": "m_1", "threadId": "t_1", "from": "민지 <minji@example.com>", "subject": "회의 메모 공유",
         "date": "2026-09-28T09:12:00+09:00", "snippet": "오늘 회의 메모 첨부합니다", "kind": "unreplied"},
        {"id": "m_2", "threadId": "t_2", "from": "서준 <seojun@example.com>", "subject": "주말 일정",
         "date": "2026-09-27T20:40:00+09:00", "snippet": "토요일 오후 괜찮으세요?", "kind": "unreplied"},
    ]}


SKILLS = [
    {"name": "doc-convert", "summary": "문서를 PDF·docx 로 변환한다", "paid": False, "long": False},
    {"name": "video-summary", "summary": "영상 링크를 받아 요약한다(유료 API)", "paid": True, "long": True},
]


def run_op(op, args, confirm_id, token_in):
    if op == "agenda":
        return ok(fake_agenda(args))
    if op == "brief_latest":
        return ok({"brief": {"date": time.strftime("%Y-%m-%d"), "text": "오늘 일정 2건, 답장할 메일 2통."}})
    if op in ("mail_triage", "mail_awaiting", "mail_search"):
        return ok(fake_mail_items())
    if op == "mail_read":
        return ok({"id": args.get("id", "m_1"), "threadId": "t_1", "from": "민지 <minji@example.com>",
                   "to": "me@example.com", "cc": "", "subject": "회의 메모 공유",
                   "date": "2026-09-28T09:12:00+09:00", "body": "안녕하세요, 오늘 회의 메모입니다.\n- 다음 주 발표 준비"})
    if op == "memory_search":
        return ok({"items": [{"slug": "grocery", "title": "장보기 규칙", "body": "우유는 저지방으로"}]})
    if op == "memory_get":
        return ok({"slug": args.get("ref", "grocery"), "title": "장보기 규칙", "body": "우유는 저지방으로"})

    if op in WRITE_OPS:
        if not confirm_id:
            return fail(400, "need_confirm", "쓰기 op 는 confirmId 가 필요하다")
        with LOCK:
            if confirm_id in DONE_CONFIRM:
                first = dict(DONE_CONFIRM[confirm_id]); first["replay"] = True
                return ok(first)
        data = {
            "memory_add": {"slug": "note-" + uuid.uuid4().hex[:6], "title": args.get("title", "")},
            "event_create": {"id": "ev_" + uuid.uuid4().hex[:6], "calendarId": "cal_home"},
            "event_delete": {"deleted": True},
            "mail_send": {"id": "mail_" + uuid.uuid4().hex[:8]},
            "mail_reply": {"id": "mail_" + uuid.uuid4().hex[:8], "to": "minji@example.com", "subject": "Re: 회의 메모 공유"},
        }[op]
        with LOCK:
            DONE_CONFIRM[confirm_id] = data
        return ok(data)

    if op == "skill_search":
        return ok({"skills": SKILLS, "total": len(SKILLS)})
    if op == "skill_view":
        return ok({"name": args.get("name", "doc-convert"), "path": "SKILL.md",
                   "text": "# doc-convert\n\n사용법: `python3 scripts/convert.py <입력> --to pdf`",
                   "policy": {"rules": {"auto": ["--help"], "confirm": ["scripts/convert.py"]}}})
    if op == "mcp_tools":
        return ok({"servers": [{"server": "notes", "summary": "가짜 메모 서버",
                                "tools": [{"name": "list_notes", "args": "query", "class": "auto",
                                           "description": "메모 목록"}]}]})

    if op in GATED_BY_POLICY:
        # 정책상 확인이 필요하다고 가정한다: 첫 호출 → need_confirm, 승인 뒤 같은 요청 + confirmId + confirmToken
        tok = confirm_token(op, {k: v for k, v in args.items() if k not in ("sessionId", "runId", "title")})
        if not confirm_id:
            text = f"{op}: " + (" ".join(args.get("argv", [])) if op == "run" else json.dumps(args, ensure_ascii=False))
            return 400, {"ok": False, "error": {"code": "need_confirm", "message": "확인이 필요하다"},
                         "data": {"confirm": {"text": text, "class": "confirm"}, "confirmToken": tok}}
        if not token_in or not hmac.compare_digest(token_in, tok):
            return fail(400, "bad_args", "confirmToken 이 요청과 다르다")
        if op == "mcp_call":
            return ok({"text": "메모 2개: 장보기 목록, 회의 메모", "isError": False})
        if args.get("background"):
            return ok({"jobId": new_job("run", args.get("title") or "맡긴 실행", args)["jobId"], "mode": "job"})
        att = add_file("result.txt", "text/plain", "변환 완료(가짜 결과)\n".encode())
        return ok({"exitCode": 0, "stdout": "완료: result.txt", "stderr": "", "attachments": [att],
                   "workId": "w_" + uuid.uuid4().hex[:8]})

    if op == "code_run":
        att = add_file("chart.txt", "text/plain", "가짜 그래프 자리\n".encode())
        return ok({"exitCode": 0, "stdout": "168\n", "stderr": "", "attachments": [att],
                   "workId": "w_" + uuid.uuid4().hex[:8]})
    if op == "job_status":
        with LOCK:
            jobs = [public_job(j) for j in JOBS.values()]
        if args.get("jobId"):
            jobs = [j for j in jobs if j["jobId"] == args["jobId"]]
        return ok({"jobs": jobs[-5:]})
    if op == "job_cancel":
        with LOCK:
            j = JOBS.get(args.get("jobId", ""))
            if not j:
                return fail(404, "not_found", "없는 작업")
            if j["state"] in ("done", "failed", "cancelled"):
                return ok({"jobId": j["jobId"], "state": j["state"], "cancelled": False})
            j["state"] = "cancelled"; bump(j)
        return ok({"jobId": j["jobId"], "cancelled": True})
    if op == "claude_task":
        if not args.get("task"):
            return fail(400, "bad_args", "task 가 비었다")
        job = new_job("claude_task", args.get("title") or args["task"][:30], args, skill="claude")
        return ok({"jobId": job["jobId"], "state": job["state"]})
    if op == "job_answer":
        with LOCK:
            j = JOBS.get(args.get("jobId", ""))
            if not j:
                return fail(404, "not_found", "없는 작업")
            ask = j.get("ask")
            if j.get("answered", {}).get(args.get("reqId")):
                return ok({"jobId": j["jobId"], "reqId": args.get("reqId"),
                           "decision": j["answered"][args["reqId"]], "replay": True})
            if not ask or ask["reqId"] != args.get("reqId"):
                return fail(400, "bad_args", "지금 기다리는 확인이 아니다")
            if not hmac.compare_digest(str(args.get("confirmToken", "")), ask["confirmToken"]):
                return fail(400, "bad_args", "confirmToken 이 다르다")
            decision = "allow" if args.get("allow") else "deny"
            j.setdefault("answered", {})[ask["reqId"]] = decision
            j["ask"] = None; j["state"] = "running"; j["_decision"] = decision; bump(j)
        return ok({"jobId": j["jobId"], "reqId": args["reqId"], "decision": decision, "replay": False})
    return fail(400, "bad_op", f"모르는 op: {op}")


# ─────────────────────────── 가짜 job 진행 ───────────────────────────

def public_job(j):
    return {k: v for k, v in j.items() if not k.startswith("_") and k != "answered"}


def new_job(tool, title, args, skill=None):
    jid = "j_" + uuid.uuid4().hex[:10]
    job = {"jobId": jid, "tool": tool, "skill": skill or args.get("skill", ""), "title": title,
           "sessionId": args.get("sessionId", ""), "runId": args.get("runId", ""),
           "state": "queued", "progress": "", "ask": None}
    with LOCK:
        JOBS[jid] = job; bump(job)
    threading.Thread(target=drive_job, args=(jid,), daemon=True).start()
    return job


def drive_job(jid):
    """queued → running → (claude_task 면 waiting: 폰 확인) → done. 시간은 짧게 흉내만."""
    time.sleep(1.0)
    with LOCK:
        j = JOBS[jid]
        if j["state"] == "cancelled":
            return
        j["state"] = "running"; j["progress"] = "시작"; bump(j)
    if j["tool"] == "claude_task":
        time.sleep(1.5)
        with LOCK:
            if j["state"] == "cancelled":
                return
            req = "r_" + uuid.uuid4().hex[:8]
            text = "Write memo.md\n\n# 회의 메모 요약\n- 다음 주 발표 준비"
            tok = hashlib.sha256(f"{jid}|{req}|Write|{text}".encode()).hexdigest()
            j["state"] = "waiting"
            j["ask"] = {"reqId": req, "tool": "Write", "text": text, "confirmToken": tok,
                        "askedAt": now_ms(), "expiresAt": now_ms() + 10 * 60 * 1000}
            bump(j)
        for _ in range(600):             # 폰 답을 최대 10분 기다린다
            time.sleep(1)
            with LOCK:
                if j["state"] != "waiting":
                    break
        with LOCK:
            if j["state"] == "waiting":
                j["state"] = "running"; j["ask"] = None; j["_decision"] = "deny"; bump(j)
    time.sleep(1.0)
    with LOCK:
        if j["state"] == "cancelled":
            return
        denied = j.get("_decision") == "deny"
        j["state"] = "done"; j["progress"] = ""
        if denied:
            j["result"] = "요청한 파일 쓰기가 거부돼 요약만 남겼다.\n거부된 동작 1개"
            j["attachments"] = []
        else:
            j["result"] = "회의 메모를 요약해 memo.md 로 저장했다.\n첨부 1개"
            j["attachments"] = [add_file("memo.md", "text/markdown", "# 회의 메모 요약\n- 다음 주 발표 준비\n".encode())]
        bump(j)


# ─────────────────────────── HTTP ───────────────────────────

class Handler(BaseHTTPRequestHandler):
    server_version = "fold-agent-mock/1"

    def log_message(self, fmt, *a):   # 본문·토큰은 찍지 않는다. 경로·상태만
        pass

    def _send(self, status, obj=None, raw=None, mime="application/json"):
        body = raw if raw is not None else json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", mime + ("; charset=utf-8" if raw is None else ""))
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        print(f"{self.command} {urlparse(self.path).path} -> {status}")

    def _auth(self):
        h = self.headers.get("Authorization", "")
        if not h.startswith("Bearer ") or not hmac.compare_digest(h[7:], TOKEN):
            self._send(*fail(401, "auth", "토큰이 없거나 다르다"))
            return False
        return True

    def do_GET(self):
        u = urlparse(self.path)
        if not u.path.startswith(PREFIX) or not self._auth():
            if not u.path.startswith(PREFIX):
                self._send(404, {"ok": False, "error": {"code": "not_found", "message": "없는 경로"}})
            return
        p = u.path[len(PREFIX):]
        if p == "/health":
            return self._send(*ok({"version": "1", "ops": sorted(ALL_OPS), "mailTestOnly": True}))
        if p.startswith("/files/"):
            fid = unquote(p[len("/files/"):])
            if fid not in FILES:
                return self._send(*fail(404, "not_found", "없는 파일"))
            name, mime, data = FILES[fid]
            return self._send(200, raw=data, mime=mime)
        if p == "/jobs":
            q = parse_qs(u.query)
            since = int((q.get("since") or ["0"])[0] or 0)
            wait = min(int((q.get("wait") or ["0"])[0] or 0), 25)
            deadline = time.time() + wait
            while True:
                with LOCK:
                    changed = [public_job(j) for j in JOBS.values() if j["_seq"] > since]
                    cur = CURSOR[0]
                if changed or time.time() >= deadline:
                    return self._send(*ok({"jobs": changed, "cursor": str(cur)}))
                time.sleep(0.5)
        self._send(*fail(404, "not_found", "없는 경로"))

    def do_POST(self):
        u = urlparse(self.path)
        if not u.path.startswith(PREFIX):
            return self._send(404, {"ok": False, "error": {"code": "not_found", "message": "없는 경로"}})
        if not self._auth():
            return
        n = int(self.headers.get("Content-Length") or 0)
        p = u.path[len(PREFIX):]
        if p == "/upload":
            if n > 25 * 1024 * 1024:
                return self._send(*fail(413, "too_big", "25MB 초과"))
            name = unquote(self.headers.get("X-File-Name", ""))
            if not name:
                return self._send(*fail(400, "bad_args", "X-File-Name 이 없다"))
            data = self.rfile.read(n)
            fid = "up_" + uuid.uuid4().hex[:12]
            safe = name.replace("/", "_").replace("\\", "_").lstrip(".")[:200]
            UPLOADS[fid] = {"name": safe, "mime": self.headers.get("Content-Type", "application/octet-stream"), "size": len(data)}
            return self._send(*ok({"fileId": fid, **UPLOADS[fid]}))
        if p == "/exec":
            if n > 64 * 1024:
                return self._send(*fail(400, "bad_args", "본문 64KB 초과"))
            try:
                body = json.loads(self.rfile.read(n) or b"{}")
            except ValueError:
                return self._send(*fail(400, "bad_args", "JSON 이 아니다"))
            op = body.get("op", "")
            if op not in ALL_OPS:
                return self._send(*fail(400, "bad_op", f"허용 목록에 없는 op: {op}"))
            status, obj = run_op(op, body.get("args") or {}, body.get("confirmId"), body.get("confirmToken"))
            return self._send(status, obj)
        self._send(*fail(404, "not_found", "없는 경로"))


def main():
    ap = argparse.ArgumentParser(description="폴드 에이전트 /api/phone 목 서버")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8787)
    a = ap.parse_args()
    print(f"mock /api/phone on http://{a.host}:{a.port}{PREFIX}  (토큰은 MOCK_TOKEN 환경변수)")
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
