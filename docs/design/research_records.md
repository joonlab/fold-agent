# 작업 기록(채팅방식) 데이터 설계 — 제목·설명·일시·보존·삭제

작성 2026-09-26 · 대상 `이 저장소` · 근거는 코드 줄 번호와 `_data/phone/`(22:19 수거본) 실측. 확인 못 한 것은 「미확인」.
폰에는 아무 명령도 보내지 않았다(수거본만 읽음). 개인 메시지 문구는 옮기지 않고 구조·통계만 적는다.

---

## 0. 결론 요약

1. **단위 = 세션(채팅방) 목록 + 방 안의 턴 카드(run 1개 = 턴 1개).** 지금 파일 구조가 이미 그렇다(턴마다 `run` id). 바꿀 것은 세션 메타 필드 추가와 목록용 인덱스.
2. **제목·설명**: 턴이 끝나는 순간 규칙 기반으로 즉시 채우고(지연 0), 세션의 첫 턴(그리고 3턴마다) 뒤에 **LLM 한 번을 비동기로** 돌려 세션 제목·요약을 덮어쓴다. 사용자가 고친 제목은 절대 덮어쓰지 않는다(`titleSource`).
3. **삭제**: 세션 → 휴지통(30일) → 영구 삭제 시 runs 파일 삭제 + recipes.jsonl 에서 그 run 줄을 **압축 재작성으로 제거**(bad 줄 방식은 재사용하지 않는다 — 아래 3.3). learned.json 은 run 과 연결이 없어 손대지 않는다.
4. **실행 중인 세션은 삭제·보관 불가**, 지금 이어 가는 세션을 지우면 새 세션 id 로 넘긴다.
5. **인덱스 필요** — 단 `sessions/` 폴더 안에 두면 `Session.all` 이 세션으로 읽는다. `files/records/index.json` 에 둔다. 세션 파일이 정본, 인덱스는 언제든 재구성 가능한 캐시.
6. **가장 큰 기존 결함 2개**를 이번에 같이 고쳐야 한다: (a) `Session.save` 가 메모리 사본 전체를 덮어써 UI 에서 바꾼 제목·고정이 실행 끝에 지워진다, (b) 비원자적 쓰기 + 읽기 실패 시 빈 세션으로 조용히 대체 → 다음 턴이 옛 턴 전부를 덮어써 날린다.

---

## 1. 현재 구조 (코드)

| 파일 | 위치 | 쓰는 곳 | 비고 |
|---|---|---|---|
| 세션 | `files/sessions/<id>.json` | `Session.addTurn` → `save`(`Session.kt:28-37, 62-71`) | `{id,title,created,updated,turns[]}` · 턴 `{run,request,final,status,steps,messages,t}` |
| 실행 기록 | `files/runs/<yyyyMMdd>/<runId>.jsonl` | `TraceLog.event`(`TraceLog.kt:24-32`) | runId=`yyyyMMdd-HHmmss-SSS`, 날짜 폴더=runId 앞 8자 |
| 성공 경로 | `files/recipes.jsonl` | `Knowledge.addRecipe`(`Knowledge.kt:113`), `markBad`(`:117`) | 성공 줄 `{run,request,steps,ms,pkg,t}` · bad 줄 `{run,bad[,why]}` |
| 자동 학습 | `files/learned.json` | `Knowledge.addAlias/markGesture` | `{aliases{},gesturePkgs{}}` — **run id 없음** |
| 이어 가는 세션 | SharedPreferences `session` | `Prefs.sessionId/setSessionId`(`Prefs.kt:29-30`) | |

- 세션 선택: `AgentService.startTask`(`AgentService.kt:63-83`) — `meta.source=="adb"` 면 `Session.load(meta.session ?: "adb-test")`, 아니면 `Session.current`.
- source 값: `"voice"`(`MainActivity.kt:276`), `"text"`(`:603`), `"adb"`(`:84`). **세션 파일에는 source 가 없다** — run_start 이벤트에만 있다(`Agent.kt:267-271`, `meta` 를 그대로 복사).
- 목록: `MainActivity.showHistory`(`:488-500`)가 **메인 스레드에서** `Session.all` → 파일 전부를 `load`(턴·messages 까지 전부 파싱) → `AlertDialog` 문자열 목록. 제목=`request.take(40)`(`Session.kt:29`).
- 👍/👎: `AgentService.kt:75-77` → `Agent.feedback`(`Agent.kt:374-377`) → trace `feedback` + 👎면 `markBad`. **세션 턴에는 평가가 저장되지 않는다.**

### 맥 쪽 소비자
- `dev.sh analyze` = `pull-logs`(`adb pull files/. → _data/phone`, **덮어쓰기만 하고 지우지 않음**, `dev.sh:63-67`) + `tools/analyze.py`.
- `analyze.py` 는 `runs/*/*.jsonl`(`:21`)과 `recipes.jsonl` 의 `bad` 줄(`:93-106`, bad 를 👎로 계산)만 읽는다. **sessions 는 읽지 않는다.** `tools/` 의 다른 스크립트·`bench.sh` 에서도 sessions 참조 없음(grep 확인).
- `foldagent` 스킬(`(개인 운영 수칙 스킬, 비공개):42`)은 맥에서 `{"run","bad":true,"why"}` 줄을 adb 로 append 한다. `:65` 는 run_start 의 `session`·`turn` 으로 턴 맥락을 본다.

---

## 2. 실측 (`_data/phone/`, 2026-09-26 22:19 수거본 — 하루치, QA 가 몰린 날)

| 항목 | 값 |
|---|---|
| 세션 파일 | **89개 · 681KB** (타임스탬프 id 21개 = 사용자 대화, 이름 id 68개 = adb 시험: `adb-test`, `qa*` 등) |
| 턴 | 196개 (ok 132 · failed 44 · cancel 17 · error 2 · stuck 1) |
| 세션당 턴 | 중앙값 1 · 최대 77(`adb-test`) · 사용자 세션 최대 5 |
| 세션 파일 크기 | 중앙값 ~3KB · 최대 174KB(`adb-test`) · 사용자 세션 최대 50KB |
| 턴 1개 크기 | 중앙값 2.0KB · p90 8.2KB · 최대 30KB (messages 가 대부분) |
| 요청 길이 | 중앙값 31자 · p90 106자 · 최대 195자 · **40자 초과 71/196(36%)** → 지금 제목(`take(40)`)은 셋 중 하나가 잘린다 |
| 답(final) 길이 | 중앙값 40자 · p90 83자 · 최대 299자 |
| 턴당 단계 | 중앙값 1 · p90 12 · 최대 54 |
| runs 파일 | **232개 · 9.6MB** · 중앙값 33KB · 최대 189KB · 날짜 폴더 1개(20260926) |
| run 출처(run_start.source) | adb 189 · voice 42 · text 1 |
| 세션 ↔ run 연결 | 턴 196개 전부 run 파일 있음 · 두 세션에 걸친 run 0 · run_start.session 과 실제 세션 불일치 0 |
| 어느 세션에도 없는 run | **36개**(run_start 에 session 필드가 없는 32개 = 세션 도입 전 빌드 추정, run_end 없는 1개 포함) |
| 타임스탬프 세션 안의 adb 턴 | **4개** — adb 분리(`AgentService.kt:65-67` 주석, 2026-09-26) 이전 것으로 추정 |
| recipes.jsonl | 182줄 · 74KB · 성공 147(키 `ms,pkg,request,run,steps,t`) · bad 35(`why` 있음 31 = 맥 수동, 없음 4 = 폰 👎) · 성공 147 중 **adb 출처 134** |
| learned.json | 291B · aliases 2 · gesturePkgs 4 |

---

## 3. 설계

### 3.1 기록의 단위

**채팅방(세션) 목록 → 방 안의 턴 카드** 구조가 맞다.
- 사용자 요구 「시킨 작업마다 제목·설명·일시」는 **턴 카드**가 충족한다: 제목=요청 요약, 설명=결과(final) + 상태 칩(✅/⚠️/⏹) + 단계 수·소요, 일시=`t`.
- 목록 행은 **세션**: 세션 제목 · 요약 1줄 · 마지막 일시 · 턴 수 · 마지막 상태 · 고정 핀. 사용자 세션의 턴 수가 중앙값 1이라, 목록이 사실상 「작업 목록」처럼도 읽힌다(참고 앱 Claude 기록 `cchistory` 도 `name`/`preview`/`description`/`tags` 목록 + 대화 상세 구조, `.../history/src/main/java/kr/joonlab/cchistory/Data.kt:18-37`).
- 삭제·보관 단위는 **세션만**(v1). 턴 단위 삭제는 다음 요청이 물려받는 이전 대화(`historyMessages`, `Session.kt:40-60`)를 바꾸므로 뒤로 미룬다(필요하면 턴에 `hidden` 표시 + history 에서도 제외).

#### 세션 파일 스키마 v2 (하위 호환)

```jsonc
{
  "v": 2,
  "id": "20260926-213737",
  "title": "…", "titleSource": "rule" | "llm" | "user",
  "summary": "…",            // 1~2문장, 없으면 ""
  "source": "voice" | "text" | "popup" | "adb",   // 첫 턴의 출처
  "pinned": false,
  "archived": false,          // 보관함(자동 삭제 없음)
  "trashedAt": 0,             // >0 이면 휴지통(파일은 sessions/.trash/ 로 이동)
  "created": 0, "updated": 0,
  "turns": [
    { "run": "…", "request": "…", "final": "…", "status": "ok", "steps": [], "messages": [], "t": 0,
      "title": "…",            // 새: 턴 제목(≤24자)
      "source": "voice",       // 새
      "ms": 0,                 // 새: 소요
      "rating": null }         // 새: true/false/null — 👍👎를 턴에도 남긴다
  ]
}
```

옛 파일 읽기 규칙(`optString/optBoolean` 기본값으로 전부 흡수):
- `v` 없음 → v1. `titleSource` 없음 → `"rule"`(옛 제목은 `request.take(40)` 규칙이었다).
- `source` 없음 → id 가 `^\d{8}-\d{6}$` 가 아니면 `"adb"`(실측: 이름 id 68개 전부 adb). 타임스탬프 id 면 첫 턴 run 의 `run_start.source` 를 읽어 채운다(인덱스 최초 구축 때 1회, runs 파일이 없으면 `"voice"`).
- 턴의 `title/source/ms/rating` 없음 → 화면에서 `request` 로 대체.
- 저장은 항상 v2 로(읽은 필드 + 새 필드).

### 3.2 제목·설명 생성

| 방식 | 지연 | 비용 | 실패 | 품질 |
|---|---|---|---|---|
| 규칙 | 0 | 0 | 없음 | 요청이 길면(36%) 잘림, 음성 인식 오기 그대로 |
| LLM 1회 | 실측 없음. 같은 모델의 짧은 판단 질의가 3.0s 였다는 기록(`Prefs.kt:8` 주석) → 수 초 추정 | 입력 ~수백 토큰. 금액은 **미확인**(홈맥 OpenAI 호환 프록시 경유 — 구독 기반이면 사실상 0일 수 있으나 확인 안 함) | 네트워크·프록시 장애 → 폴백 필요 | 요약·교정 가능 |

**추천: 규칙 즉시 + LLM 비동기 덮어쓰기.**
- 규칙(동기, 턴 저장과 함께): 턴 `title` = 요청을 공백 정리 후 24자 넘으면 첫 문장/어절 경계에서 자르고 `…`. 세션 `title` = 첫 턴 title(비어 있을 때만). 세션 `summary` = 마지막 턴 `final` 의 첫 문장(60자).
- LLM(비동기, 실패하면 아무것도 안 함 = 규칙값 유지): 조건 `titleSource != "user"` 이고 (턴 수 == 1 또는 턴 수 % 3 == 0). 입력 = 요청들(각 120자)·결과(각 200자)만. 화면 전문·메시지 제외. 출력 JSON `{"title":"≤20자","summary":"≤60자","turnTitle":"≤20자"}`. 타임아웃 8s. `source=="adb"` 세션은 **LLM 생략**(시험 세션에 토큰 쓰지 않음).
- 어디에 끼우나:
  - 규칙: `Session.addTurn`(`Session.kt:28-37`) 안(→ 새 구조에선 `RecordStore.appendTurn`). `Agent.run()` 의 호출부는 그대로 `Agent.kt:363` `session.addTurn(runId, task, final ?: "", status, steps, messages.drop(1))` — 인자에 `meta.optString("source")` 와 `totalMs`(`:357` 에서 계산됨)를 더한다.
  - LLM: `Agent.kt:363` 바로 뒤가 아니라 **`AgentService.startTask` 의 스레드에서 `agent.run()` 이 돌아온 뒤**(`AgentService.kt:71-72`, `running = null` 이후) 별도 스레드로. 이유: `run()` 은 363 뒤에 `emit("finish")`(364)·`speak`(370) 를 하므로 거기서 기다리면 사용자 체감이 늦어진다. `running=null` 뒤라 새 작업이 같은 세션에 턴을 붙일 수 있으므로, 결과 반영은 반드시 아래 `RecordStore.patch`(파일을 다시 읽고 메타만 바꾸는 방식)로 한다.
- adb 시험 세션: 목록 기본 필터에서 **숨김**(`source=="adb"`), 필터 칩 「시험」으로 따로 모아 본다. 실측상 세션의 76%(68/89)가 시험이라 숨기지 않으면 목록이 시험으로 덮인다.

### 3.3 삭제의 정합성

**상태 전이**: `활성 ⇄ 보관(archived)` · `활성/보관 → 휴지통(trashedAt) → 영구 삭제(purge)` · `휴지통 → 복원`.

| 동작 | 세션 파일 | runs/`<날짜>`/`<runId>`.jsonl | recipes.jsonl | learned.json |
|---|---|---|---|---|
| 보관 | `archived=true` | 그대로 | 그대로 | 그대로 |
| 휴지통 | `trashedAt=now` 쓰고 `sessions/.trash/<id>.json` 으로 이동 | 그대로 | 그대로(복원 가능해야 하므로) | 그대로 |
| 복원 | `.trash` → `sessions/`, `trashedAt=0`. 같은 id 파일이 이미 있으면(adb 이름 재사용) `<id>-r<n>` 으로 | — | — | — |
| 영구 삭제(30일 경과 자동 또는 「휴지통 비우기」) | 파일 삭제 | **턴의 run 전부 삭제**(날짜 폴더 = runId 앞 8자, 폴더가 비면 rmdir) | **그 run 들의 줄(성공·bad 모두)을 뺀 사본을 임시 파일에 쓰고 rename**(Knowledge 의 락 안에서) | 손대지 않음 — run id 가 없고 개인 내용이 아니다(앱 별칭·좌표탭 앱 목록) |
| 영구 삭제 원장 | `files/records/purged.jsonl` 에 `{run, session, t}` append | | | |

- **bad 줄 방식을 재사용하지 않는 이유**: (1) 성공 줄의 `request` 문구(개인 내용)가 파일에 그대로 남아 「삭제」가 아니다. (2) `analyze.py:93-106` 이 bad 를 👎로 센다 — 이미 맥 수동 bad 가 섞여 문제라는 기록이 있다((인계 문서):51`, 스킬 `:120`). 삭제를 bad 로 적으면 평가 통계가 더 오염된다.
- 동시성: 폰의 `addRecipe`(append)와 맥 스킬의 adb append(`SKILL.md:42`)가 재작성과 겹칠 수 있다. 폰 내부는 `Knowledge` 의 `@Synchronized` 로 막고, 맥 append 와의 경합은 재작성 직전·직후 크기 비교로 감지해 재시도. 완전 차단은 **미확인**(adb 쓰기와 앱 쓰기 사이에 파일 잠금 없음).
- 「학습은 남기고 기록만 지우기」 선택지: 영구 삭제에서 recipes 줄을 지우지 않으면 에이전트가 그 경로를 계속 참고한다(대신 요청 문구가 남는다). **사용자 결정 필요** — 기본값 추천은 「같이 지움」.
- **맥 사본**: `pull-logs` 는 지우지 않으므로 폰에서 지워도 `_data/phone/runs` 에 남는다. `analyze` 는 깨지지 않는다(runs glob·recipes 형식 불변, `purged.jsonl` 은 `runs/*/*.jsonl` glob 밖). 맥 사본까지 지우려면 `pull-logs` 뒤에 `purged.jsonl` 의 run 을 `_data/phone/runs` 에서 휴지통으로 옮기는 단계를 추가(선택, CLAUDE.md 삭제 규칙상 rm 이 아니라 휴지통 + 로그).
- 👎 표시도 세션 턴 `rating` 에 같이 저장(`AgentService.kt:75-77` 의 콜백에서 `RecordStore.rate(runId, good)`).

**규칙**
- **작업 중(busy)**: 실행 중인 세션(`AgentService` 에 `runningSessionId` 노출 필요 — 지금은 `running: Agent?` 만 있다, `AgentService.kt:33,60`)은 휴지통·보관 버튼 비활성 + 「작업이 끝난 뒤에」 안내. 이름 바꾸기·고정은 허용(3.5 의 patch 로 안전). 다른 세션은 작업 중에도 삭제 가능.
- **지금 이어 가는 세션(`Prefs.sessionId`)을 휴지통으로**: 즉시 `Prefs.setSessionId(newId())`(파일은 첫 턴에 생긴다 — `Session.current` 와 같은 방식). 복원해도 자동으로 되돌리지 않고, 목록에서 눌러 이어 가게 한다.
- **되돌리기**: 휴지통 이동 직후 스낵바 「삭제했어요 · 실행 취소」(5초) → `restore`. 영구 삭제(휴지통 비우기)만 확인 대화상자. 이것은 앱 UI 안의 사용자 조작이라 에이전트의 발송·결제·삭제 확인 게이트(`AgentService.confirm`, `Agent.gate`)와 별개이며, 그 게이트는 건드리지 않는다.
- 자동 영구 삭제 시점: 앱/서비스 시작 시 하루 1회, `trashedAt + 30일 < now`. 보관(archived)은 자동 삭제 대상 아님.
- 어느 세션에도 없는 옛 run 36개: 목록에 안 나오고 purge 대상도 아님(현행 유지). 필요하면 별도 「옛 실행」 정리 기능.

### 3.4 목록 성능과 인덱스

- `Session.all`(`Session.kt:113-116`)은 파일마다 전체 JSON(턴 messages 포함)을 파싱한다. 지금 89개·681KB. 폰에서의 소요는 **미측정**(폰 명령 금지로 재지 않음). 호출이 메인 스레드(`MainActivity.kt:489`)라 커지면 바로 버벅임으로 보인다.
- 증가 추정: 사용자 턴 평균 ~3.5KB(681KB/196). 하루 사용자 요청 20건이면 월 ~2MB, 1년 ~25MB를 목록 열 때마다 파싱 → 인덱스가 필요하다. 목록에 필요한 값은 세션당 ~200B라 1,000세션도 ~200KB.
- **함정**: `sessions/index.json` 으로 두면 `Session.all` 의 `*.json` 필터가 인덱스를 id `index` 인 세션으로 읽는다. → **`files/records/index.json`** 에 둔다(`.trash/` 는 디렉터리라 `listFiles` 필터에 안 걸림 — 비재귀).
- 인덱스는 캐시: `{"v":1, "sessions": {id: {title,titleSource,summary,source,pinned,archived,trashedAt,created,updated,turns,lastStatus,lastRequest,fileMtime}}}`. 모든 변경은 RecordStore 를 거쳐 세션 파일과 인덱스를 같은 락 안에서 갱신. 열 때 `sessions/` 파일 목록·mtime 과 대조해 어긋난 항목만 다시 읽고, 인덱스가 깨졌거나 버전이 다르면 전체 재구성(첫 설치 때 3.1 의 source 추론도 여기서).
- 참고 앱도 같은 패턴(목록=인덱스 캐시 `IndexCache`, 상세=메시지 페이지 로드; `cchistory/Data.kt:44-53,180-199`).

### 3.5 기존 결함 — 이번에 같이 고칠 것

1. **덮어쓰기 경합**: `Session.load` 는 매번 새 인스턴스를 만들고(`Session.kt:86-98`), `save` 는 그 인스턴스의 `title/created/updated/turns` 전체를 쓴다(`:62-71`). Agent 가 실행 시작 때 잡은 인스턴스로 끝에 `addTurn` 하면 **실행 도중 UI 에서 바꾼 제목·고정·보관이 지워진다**. `@Synchronized` 는 인스턴스별이라 서로 다른 인스턴스 사이를 막지 못한다. → 모든 쓰기를 `RecordStore` 의 전역 락 안에서 「디스크에서 다시 읽기 → 변경 → 원자적 쓰기」로.
2. **비원자적 쓰기 + 조용한 빈 세션**: `file.writeText` 도중 앱이 죽으면 파일이 깨지고, `load` 는 `runCatching` 으로 빈 세션을 돌려준다 → 다음 `addTurn` 이 옛 턴을 전부 잃은 채 덮어쓴다. → 임시 파일 쓰기 + `renameTo`, 파싱 실패 파일은 `sessions/.corrupt/` 로 옮기고 새 id 로 시작.
3. **휴지통 세션 부활**: adb `--es session qa10` 처럼 이름으로 `Session.load` 하면, 휴지통의 같은 id 와 별개로 새 파일이 생긴다 → 복원 시 id 충돌(3.3 의 `-r<n>` 규칙으로 처리).

### 3.6 Kotlin 데이터 계층 초안

```kotlin
// 목록 한 줄 — 인덱스에서 바로 만든다(턴·messages 를 읽지 않음)
data class RecordSummary(
    val id: String, val title: String, val titleSource: String, val summary: String,
    val source: String, val pinned: Boolean, val archived: Boolean, val trashedAt: Long,
    val created: Long, val updated: Long, val turns: Int, val lastStatus: String, val lastRequest: String,
)

data class TurnCard(
    val run: String, val title: String, val request: String, val final: String, val status: String,
    val t: Long, val ms: Long, val source: String, val rating: Boolean?, val steps: List<Step>,
)
data class Step(val tool: String, val summary: String)

enum class Shelf { ACTIVE, ARCHIVED, TRASH }
data class RecordFilter(
    val shelf: Shelf = Shelf.ACTIVE,
    val includeTests: Boolean = false,      // source == "adb"
    val query: String = "",                 // 제목·요약·lastRequest 부분 일치
    val status: String? = null,             // ok/failed/…
)

sealed interface RecordEvent {
    data class Changed(val id: String) : RecordEvent          // 턴 추가·메타 변경
    data class Removed(val id: String, val toTrash: Boolean) : RecordEvent
    data class Restored(val id: String) : RecordEvent
    data class Purged(val ids: List<String>, val runs: Int) : RecordEvent
    object Rebuilt : RecordEvent
}

interface RecordStore {
    // 읽기
    fun list(filter: RecordFilter = RecordFilter()): List<RecordSummary>   // pinned 먼저, 그다음 updated 내림차순
    fun get(id: String): List<TurnCard>?                                    // 휴지통 포함
    fun trace(runId: String): File?                                         // 턴 「자세히」 — runs 파일(지연 로드)

    // 에이전트 쪽
    fun appendTurn(id: String, turn: JSONObject, source: String)            // Agent.kt:363 대체. 규칙 제목·요약 동시 설정
    fun historyMessages(id: String): List<JSONObject>                       // Session.historyMessages 이관
    fun rate(runId: String, good: Boolean)
    fun applyGenerated(id: String, title: String?, summary: String?, turnRun: String?, turnTitle: String?) // LLM 결과, titleSource=="user"면 제목 무시

    // 사용자 조작
    fun rename(id: String, title: String)                                   // titleSource="user"
    fun pin(id: String, on: Boolean)
    fun archive(id: String, on: Boolean)                                    // busy 세션이면 IllegalStateException
    fun delete(id: String)                                                  // → 휴지통. 현재 세션이면 Prefs 새 id
    fun restore(id: String): String                                         // 실제 복원된 id(충돌 시 -r<n>)
    fun purge(ids: List<String>? = null, olderThanDays: Int = 30): Int      // null = 기한 지난 휴지통 전부. runs·recipes 정리 + purged.jsonl

    // 관찰
    val events: kotlinx.coroutines.flow.SharedFlow<RecordEvent>             // Compose 로 가면 StateFlow<List<RecordSummary>> 도
    fun rebuildIndex()
}
```

- 구현(`FileRecordStore`)은 앱 전역 싱글턴(`AgentService` 와 `MainActivity` 가 같은 인스턴스·같은 락을 쓰도록 `Application` 또는 `object`). 모든 쓰기는 IO 스레드.
- `Session` 클래스는 당분간 `RecordStore` 를 감싸는 얇은 어댑터로 남겨 `Agent` 변경을 `:363` 한 줄 + `historyMessages` 호출(`:265`)로 줄인다.
- 지금 UI 는 기본 View 라 `SharedFlow` 대신 `AgentService.listeners` 같은 콜백 목록으로 시작해도 된다(코루틴 의존성 추가 여부는 **미확인** — `app/build.gradle` 을 보지 않았다).

---

## 4. 사용자 결정이 필요한 것 (메인 세션이 AskUserQuestion 으로)

1. 영구 삭제 때 **성공 경로(recipes)도 같이 지울지** — 추천: 같이 지움(요청 문구가 남기 때문). 대안: 학습은 유지.
2. 휴지통 보관 기간 **30일** 괜찮은지.
3. adb 시험 세션을 앱 목록에서 **완전히 숨길지 / 「시험」 칩으로만 볼지** — 추천: 기본 숨김 + 칩.
4. LLM 제목 생성 사용 여부(홈맥 프록시 호출이 늘어난다) — 추천: 사용자 세션만, 첫 턴·3턴마다.
5. 맥 사본(`_data/phone`)에도 삭제를 반영할지.

## 5. 출처

- 코드: `app/src/main/java/kr/joonlab/foldagent/{Session.kt,Agent.kt,TraceLog.kt,AgentService.kt,MainActivity.kt,Prefs.kt,Knowledge.kt}` (줄 번호는 2026-09-26 작업 트리 기준)
- 맥 도구: `dev.sh`, `tools/analyze.py`, `(개인 운영 수칙 스킬, 비공개)`
- 문서: (인계 문서, 비공개 — docs/JOURNEY.md 참고), (인계 문서), (인계 문서), `README.md`
- 실측 데이터: `_data/phone/{sessions,runs,recipes.jsonl,learned.json}` (통계만)
- 참고 앱: `<참고 앱 저장소>/android/history/src/main/java/kr/joonlab/cchistory/Data.kt`
