# 홈 서버 계약 — `/api/phone`

폴드 에이전트의 비서 도구·스킬 게이트웨이·Claude Code 위임은 폰 혼자 하지 않고, 집에 있는 맥(이하 「홈 서버」)에 있는 HTTP 라우트를 부릅니다. 제가 쓰는 홈 서버는 개인 인프라(메일·캘린더·기억 저장소·스킬 폴더)에 묶여 있어서 공개하지 않았습니다. 대신 폰 앱이 기대하는 **선 모양**을 여기에 적고, 같은 모양으로 가짜 응답을 돌려주는 [`mock_server.py`](mock_server.py)를 두었습니다.

- 이 문서는 원래 여러 개였던 설계 계약(비서 도구 → 스킬·실행 → Claude Code 위임 → 폰 파일 입력)을 하나로 합치고 개인 정보를 뺀 요약판입니다. 앱 코드 주석의 「계약 §…」 번호는 원문 기준이라 이 문서의 절 번호와 맞지 않을 수 있습니다.
- 폰 쪽 구현: `AssistantClient.kt`(HTTP) · `AssistantTools.kt`(비서 도구) · `CapTools.kt`(스킬·실행·MCP·위임) · `jobs/*`(맡긴 일 감시·원격 확인) · `Uploads.kt`(파일 올리기).

## 0. 안전 원칙

1. **쓰기 동작은 폰이 도구 종류만 보고 무조건 확인 창을 띄운다.** 모델이 확인을 건너뛸 인자는 없다. 40초 무응답은 거부.
2. **확인 창에는 사람이 판단할 것을 전부 넣는다.** 메일이면 받는 사람 전부·제목·본문 앞부분, 일정이면 제목·시작·끝, 명령이면 실행할 원문.
3. **판정은 서버가 결정적으로 한다.** 스킬 실행·MCP 호출·Claude Code 의 도구 사용은 서버의 정책 파일이 `auto`(바로 실행) · `confirm`(폰 확인) · `paid`(폰 확인 + 예상 비용) · `deny`(항상 차단)로 가른다. 발송·게시·공유·삭제 계열은 승인으로도 풀리지 않는다.
4. **보여 준 것 = 실행하는 것.** 확인이 필요하면 서버가 정규화한 요청의 해시(`confirmToken`)를 같이 준다. 폰은 승인하면 같은 요청에 `confirmId`(UUID)와 `confirmToken` 을 붙여 다시 보내고, 서버는 해시를 다시 계산해 다르면 거부한다.
5. **셸을 쓰지 않는다.** `run` 은 argv 배열만 받고 서버는 셸을 거치지 않고 실행한다. 파이프·리다이렉트·`;`·`&&` 는 없다.
6. **멱등.** 같은 `confirmId` 는 두 번 실행하지 않는다(서버가 24시간 기억하고 두 번째엔 첫 결과를 `replay:true` 로 돌려준다). 폰은 쓰기 요청을 재시도하지 않는다. 연결이 끊기면 「결과 불명」으로 알리고 다시 보내지 않는다.
7. **토큰·키는 로그에 남기지 않는다.** 서버 로그는 op·상태·ms 정도만. 폰도 trace·기록에 토큰을 쓰지 않는다.

## 1. 노출과 인증

- 폰 앱의 기본 주소는 빌드 설정 `foldagent.assistantBase`(예: `https://<home-server-host>/api/phone`)이고, 앱 설정 화면에서 바꿀 수 있습니다. **https 만 받습니다.**
- 저는 홈 서버를 사설망(Tailscale) 안에서만 열고, `tailscale serve` 로 HTTPS 를 붙였습니다. 공용 인터넷에 여는 구성은 권하지 않습니다.
- 모든 요청: `Authorization: Bearer <PHONE_API_TOKEN>`. 서버는 상수 시간 비교를 쓰고, 토큰이 설정되지 않았으면 `503 not_configured` 로 닫힌다(토큰 없이 열리는 경우는 없다).

## 2. 엔드포인트

| 메서드 · 경로 | 용도 |
|---|---|
| `GET /health` | `{ok:true, data:{version:"1", ops:[...], mailTestOnly:boolean}}` — 앱 설정의 「연결 확인」 |
| `POST /exec` | 본문 `{op, args, confirmId?, confirmToken?}` → 성공 `{ok:true, data}` / 실패 `{ok:false, error:{code, message}}` |
| `POST /upload` | 폰 파일 올리기. 본문 = 파일 바이트, 헤더 `Content-Type`, `X-File-Name`(UTF-8 percent-encoding) → `{fileId:"up_…", name, mime, size}`. 25MB 초과 413 |
| `GET /files/<fileId>` | 결과 첨부 받기(Bearer 필요) |
| `GET /jobs?since=<cursor>&wait=<초≤25>` | 맡긴 일 상태 long-poll → `{jobs:[JobInfo…], cursor}` |

실패 코드: `auth`(401) · `bad_op`(400, 허용 목록 밖) · `bad_args`(400) · `need_confirm`(400) · `not_allowed`(403, 차단·시험 제한) · `not_found`(404) · `timeout`(504) · `exec_failed`(502) · `not_configured`(503). 폰은 이 밖에 `cancelled`(사용자가 멈춤) · `unreachable`(연결 실패)을 스스로 붙인다.

요청 한도(제 구현 기준): 본문 64KB, op 별 시간초과 15~90초, 동기 실행 동시 3개.

## 3. op — 비서 도구

폰 도구 이름과 op 가 거의 같다. ✍ 는 쓰기 op(`confirmId` 필수).

| 폰 도구 | op | 인자 | data (폰이 읽는 최소 필드) |
|---|---|---|---|
| `agenda` | `agenda` | `from`?·`to`?(YYYY-MM-DD) · `query`? | `{events:[{id, calendarId, calendar, summary, start, end, allDay, labelName?, note?}]}` |
| `briefing` | `brief_latest` | — | `{brief:{date, text} \| null}` |
| `mail_list` | `mail_triage` · `mail_awaiting` · `mail_search` | `query`?·`n`?(≤20) | `{items:[{id, threadId, from, subject, date, snippet, kind?}]}` |
| `mail_read` | `mail_read` | `id` | `{id, threadId, from, to, cc, subject, date, body}` |
| `memory_search` / `memory_get` | 같은 이름 | `query` / `ref` | 서버 형식 그대로(목록 또는 한 건) |
| `memory_add` ✍ | `memory_add` | `title`·`body`·`tags`? | `{slug, title}` |
| `event_create` ✍ | `event_create` | `title`·`start`·`end`(ISO 8601)·`all_day`?·`note`? | `{id, calendarId}` |
| `event_delete` ✍ | `event_delete` | `event_id`·`calendar_id` | `{deleted:true}` |
| `mail_send` ✍ | `mail_send` | `to`(1~5개)·`subject`·`body`·`cc`? | `{id}` |
| `mail_reply` ✍ | `mail_reply` | `id`·`body` | `{id, to, subject}` — 원문 스레드로 이어진다 |

- **시험 제한**: 서버 환경변수로 받는 사람을 허용 목록으로 묶을 수 있다(어기면 `403 not_allowed`). `health` 의 `mailTestOnly` 로 폰에 보인다. 저는 지금도 이 제한을 켜 둔 채 씁니다.
- 앱 확인 창의 「보내는 주소」 표시는 빌드 설정 `foldagent.mailFrom` 으로 맞춘다(서버가 실제로 쓰는 주소와 같게).

## 4. op — 스킬·실행·MCP

| 폰 도구 | op | 인자 | data |
|---|---|---|---|
| `skill_search` | `skill_search` | `query`·`n`? | `{skills:[{name, summary, paid?, long?}], total}` — 정책 파일이 있는 스킬만 보인다 |
| `skill_view` | `skill_view` | `name`·`path`?·`offset`? | `{name, path, text, nextOffset?, policy?}` — 문서 본문(12,000자씩) + 실행 규칙 요약 |
| `run` | `run` | `skill`·`argv`(문자열 배열)·`background`?·`stdin`?·`inputs`?·`from_job`? | 동기: `{exitCode, stdout, stderr, attachments?, workId?}` · job: `{jobId, mode:"job", promoted?}` |
| `code_run` | `code_run` | `code`(python)·`files`?·`inputs`?·`from_job`? | `run` 동기와 같은 모양. 네트워크 없는 격리 컨테이너, 짧은 시간·작은 메모리 |
| `mcp_tools` | `mcp_tools` | `server`? | `{servers:[{server, summary, tools:[{name, args, class, description}]}]}` |
| `mcp_call` | `mcp_call` | `server`·`tool`·`args` | `{text, isError?, attachments?}` |
| `job_status` | `job_status` | `jobId`? | `{jobs:[JobInfo…]}` |
| `job_cancel` | `job_cancel` | `jobId` | `{jobId, cancelled:true}` 또는 `{jobId, state}`(이미 끝남) |

**확인 흐름**(`run`·`mcp_call`): 첫 호출 → 정책이 `auto` 면 바로 실행. `confirm`/`paid` 면

```json
{"ok": false,
 "error": {"code": "need_confirm", "message": "…"},
 "data": {"confirm": {"text": "<실행할 명령 원문>", "class": "confirm", "cost": "<paid 일 때>"},
          "confirmToken": "<sha256>"}}
```

→ 폰이 원문으로 확인 창을 띄우고, 승인이면 같은 요청 + `confirmId` + `confirmToken` 을 다시 보낸다. `deny` 면 `403 not_allowed`(다시 시도하지 말 것).

- **오래 걸리는 일**: 정책이 `long` 인 명령이거나 `background:true` 면 job 으로 돌린다. 동기 실행이 90초를 넘기면 서버가 그 자리에서 job 으로 넘기고 `promoted:true` 를 준다.
- **첨부**: `attachments:[{fileId, name, mime, size}]` 는 `GET /files/<fileId>` 로 받는다. 모델이 첨부를 고르지 않는다(지어낼 수 없게 코드가 모은다).
- **`inputs`**: `/upload` 로 받은 `fileId` 목록(최대 5). 서버가 작업 폴더의 `in/` 에 복사한다. 모델에게는 폰 경로가 아니라 이 id 만 준다.
- **`from_job`**: 앞선 작업 폴더를 이어 쓴다. 새로 생기거나 바뀐 파일만 첨부로 돌아온다.

## 5. op — Claude Code 위임

| 폰 도구 | op | 인자 | data |
|---|---|---|---|
| `claude_task` | `claude_task` | `task`(≤8000자)·`title`?·`sessionId`·`runId`·`inputs`? | `{jobId, state}` — 늘 job |
| (원격 확인 답) | `job_answer` | `jobId`·`reqId`·`allow`·`confirmToken` | `{jobId, reqId, decision:"allow"\|"deny", replay}` |

- 서버는 홈 서버의 Claude Code 를 헤드리스(`claude -p`, stream-json 입출력)로 띄우고, 모델이 도구를 쓸 때마다 오는 권한 요청(`can_use_tool`)을 판정한다: 읽기 전용은 자동, 작업 폴더 안 쓰기·그 밖의 명령은 **폰 확인**, 발송·게시·삭제·시스템 명령은 차단.
- 전역 설정의 allow 규칙이 이 판정보다 먼저 통과시키는 문제가 있어서, 위임 실행에는 모든 부작용 도구를 `ask` 로 뒤집는 설정 덮개(`--settings`)를 씌웠다. 이 부분이 위임 설계의 핵심입니다.
- 상한(제 구현): 동시 1개 · 15분(확인 대기 시간 제외) · 30턴. 확인을 10분 안에 안 하면 자동 거부.
- 확인이 필요하면 job 이 `waiting` 이 되고 `ask` 가 붙는다. 폰은 heads-up 알림(버튼 없음)을 띄우고, 누르면 앱이 원문 전체로 확인 창을 연다. 잘린 글로 승인하지 않게 알림에는 승인 버튼을 두지 않았다.

## 6. JobInfo

```jsonc
{
  "jobId": "j_…", "tool": "run" | "code_run" | "claude_task", "skill": "…",
  "title": "…", "sessionId": "…", "runId": "…",          // 결과를 맡긴 대화방에 붙이려고 폰이 넣는다
  "state": "queued" | "running" | "waiting" | "done" | "failed" | "cancelled",
  "progress": "…",
  "ask": { "reqId": "…", "tool": "Write", "text": "<원문>", "confirmToken": "…",
           "askedAt": 0, "expiresAt": 0 },                  // state 가 waiting 일 때만
  "result": "…", "attachments": [{ "fileId": "…", "name": "…", "mime": "…", "size": 0 }],
  "updatedAt": 0
}
```

폰은 모르는 state 를 running 처럼 다룬다. 끝난 job 의 결과는 폰이 한 번 더 요약해(`JobSummary`) 맡긴 대화방에 붙이고, 모델이 `then`(끝나면 이어서 할 일)을 남겼으면 화면을 쓰지 않는 실행으로 이어 간다(사슬은 3번까지 자동).

## 7. 목 서버로 확인하기

```bash
MOCK_TOKEN=dev-token python3 docs/server-contract/mock_server.py --port 8787

H='Authorization: Bearer dev-token'; B=http://127.0.0.1:8787/api/phone
curl -s -H "$H" $B/health
curl -s -H "$H" -d '{"op":"agenda","args":{}}' $B/exec
curl -s -H "$H" -d '{"op":"mail_send","args":{"to":"minji@example.com","subject":"t","body":"b"}}' $B/exec   # need_confirm
curl -s -H "$H" -d '{"op":"claude_task","args":{"task":"회의 메모 요약"}}' $B/exec
curl -s -H "$H" "$B/jobs?wait=5"                                                                            # waiting + ask
```

목 서버는 모든 `run`·`mcp_call` 에 확인을 요구하고, `claude_task` 는 1.5초 뒤 `Write memo.md` 확인을 한 번 요청한 뒤 끝나는 흉내만 냅니다. 실제 메일·일정·스킬은 건드리지 않습니다.
