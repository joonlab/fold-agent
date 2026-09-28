# 폴드 에이전트 UI/UX 새 설계 — DESIGN

작성 2026-09-26 · 설계 책임 문서(구현 작업자 4명 + 메인 세션 공용 정본)
근거: `research_muse.md`(Muse·Gemini·Siri 빛) · `research_tech.md`(팝업·빛 기술) · `research_refapps.md`(cmux 관제실·Claude 기록) · `research_records.md`(기록 데이터) · 코드 `app/src/main/java/kr/joonlab/foldagent/{MainActivity,Overlay,AgentService,Session,Agent}.kt`
시안: `docs/design/mockups.html`(3방향 비교, 테두리 빛은 CSS 로 실제로 숨쉼)
표기: **[확인]** 근거 문서·코드로 확인 · **[추정]** · **[미확인]** 실기로 판정해야 함 · **[결정]** 사용자에게 물을 것(§7)

---

## 0. 한 페이지 요약

**무엇을 만드나.** 폴드 에이전트를 「부르면 어디서든 뜨고, 일하는 동안 화면이 숨 쉬고, 끝나면 채팅방처럼 쌓이는」 에이전트로 바꾼다.

| 요구 | 설계 한 줄 | 구현 위치 |
|---|---|---|
| ① 팝업 입력 | 측면 버튼 길게 → **VoiceInteractionSession 창**에 아래서 올라오는 입력 카드(글·음성). 역할을 안 바꾸면 같은 카드를 **투명 `QuickAskActivity`** 로(두 번 누르기 → 앱 열기) | 작업 A |
| ② 테두리 빛 | 접근성 오버레이 **두 번째 창**(`FLAG_NOT_TOUCHABLE` 생성 시 고정, 절대 토글 안 함)에 네 변 그라데이션을 한 번 그리고 **알파만** 3.6초 주기로 숨쉬기. 확인 대기 중엔 숨쉬기를 멈추고 다른 색으로 고정 | 작업 B |
| ③ 작업 기록 | **세션 = 채팅방, run = 턴 카드.** 규칙 제목 즉시 + LLM 제목 비동기, `records/index.json` 캐시, 보관 · 휴지통(30일) · 영구 삭제 시 runs·recipes 정합 정리, 전역 락 + 원자적 쓰기 | 작업 C |
| ④ 대시보드 | 홈 = 「상태 카드 + 작업 기록 목록」, 대화방, 설정. **앱 화면만 Compose**, 오버레이는 View 유지. 커버 1칸 / 펼침 2칸(목록 \| 대화방) | 작업 D |
| ⑤ 벤치마킹 | Muse: 작업 카드(아이콘·제목·설명·시각)·진행 중 카드에 ■ 정지·결정론 확인 카드. Gemini: 하단 입력 카드·제안 칩. 관제실/기록 앱: 평평한 면 + 1dp 테두리 + 상태는 면 물들이기, 600dp 분기, sticky 날짜 머리 | 전 작업 공통 |

**안전 불변식(모든 작업이 지킨다)**
1. 확인 게이트: `AgentService.confirm()` 흐름·**40초 무응답 = 거부**·`hide()` 시 거부로 풀기·`Agent.gate` 판정은 **바꾸지 않는다.** UI(카드 모양·문구)만 바꾼다. 「실행」 버튼은 기본 포커스·기본 강조를 받지 않는다(§2-5).
2. 정지 버튼은 상태 패널에 **항상** 있다(작업 중 = 정지, 확인 대기 = 거부, 유휴 = 닫기). 팝업·기록 목록의 진행 중 카드에도 정지를 둔다(같은 `cancelTask()`).
3. 테두리 빛 창은 한 번도 터치를 받지 않는다. 스크린샷(`look`·`capture`·`systemScreenshot`)에 찍히지 않게 패널과 함께 숨긴다.
4. 새로 만드는 입구(팝업·세션)는 **재전달 인텐트 가드**(`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`·재생성 무시)를 둔다(스킬 「지켜야 할 것」).

**추천 방향(시안 1) 「숨결(Ember)」**: 따뜻한 먹색 바탕 + 잉걸불 붉은색 `#E5484D` 하나. 빛이 주인공이 되게 나머지는 평평하고 조용하게. 라이트 테마도 같은 토큰 체계로 만든다. 최종은 사용자 결정(§7-1).

---

## 1. 화면 목록과 흐름

```
[어느 앱이든] ──측면 버튼 길게──▶ ① 팝업 입력 카드 ──보내기──▶ (카드 닫힘) ──▶ 에이전트 조작
                                                                     │  ② 테두리 빛 숨쉬기 + ⑤ 상태 패널(정지)
                                                                     │  확인 필요 → 상태 패널 확인 카드(실행/취소, 40초)
                                                                     ▼
                                                   끝 → 패널에 결과 한 줄 + 잘했어/틀렸어 → 10초 뒤 사라짐
[런처/알림/패널의 「기록」] ──▶ ④ 홈(상태 카드 + 작업 기록 목록) ──탭──▶ ③ 대화방(턴 카드) ──이어서 시키기──▶ 에이전트
```

### 1-1. 홈(대시보드) — `MainActivity` 첫 화면
- **머리**: 로고 표식(작은 원) + 「폴드 에이전트」 + 테마(해/달) + 설정(⚙).
- **상태 카드**(한 장, 머리 아래 고정): 에이전트 상태를 글로 드러낸다(관제실 원칙 10).
  - 유휴: 「준비됨 · gpt-5.x · 홈맥 프록시」 + 큰 입력 알약 「무엇을 해 드릴까요?」(누르면 아래 입력창 포커스) + 마이크.
  - 작업 중: 면을 accent α.13 로 물들이고 「설정 › 디스플레이 여는 중 · 3단계 · 12초」 + **■ 정지**.
  - 확인 대기: 확인색 α.16 물듦 + 「확인을 기다려요 — 화면 위 패널에서 실행/취소」(여기서 실행 버튼은 두지 않는다 — 확인은 한 곳에서만, §2-5).
  - 접근성 꺼짐: 「접근성 서비스가 꺼져 있어요」 + 「접근성 설정 열기」 주 버튼(지금 `refreshStatus` 문구 계승).
- **필터 칩 줄**(가로 스크롤, 알약): 전체 · 오늘 · 고정 · 실패 · 보관함 · 휴지통 · 시험(adb, 기본 숨김). 검색은 머리의 돋보기 → 검색칸.
- **목록**: sticky 날짜 머리 「오늘」「어제」「9월 24일」. 고정 항목은 맨 위 「고정」 묶음.
- **아래**: 입력창(`Composer`: 둥근 입력칸 16sp + 마이크 + 원형 보내기). 여기서 보내면 **새 대화방**이 아니라 「지금 이어 가는 방」에 붙는다 — 현재 동작 유지. 방 전환은 목록에서.
- 새 대화: 머리 오른쪽 「새 대화」(연필+) 버튼. 작업 중이면 비활성 + 이유(현 동작 계승).

### 1-2. 기록 목록 줄(= 세션 한 개, Muse 작업 카드 형식)
| 요소 | 내용 | 비고 |
|---|---|---|
| 왼쪽 아이콘(36dp 둥근 사각) | 마지막 턴 도구 계열(앱 열기·입력·문자·지도·노트·설정) — 규칙으로 고름 | 없으면 말풍선 |
| 제목(15 SemiBold, 1줄) | `title` — 규칙/LLM/사용자 | 사용자가 고친 건 절대 덮지 않음 |
| 설명(13 dim, 2줄) | `summary` 없으면 마지막 `final` 첫 문장 | |
| 오른쪽 위 | 시각 「오후 9:41」(오늘) / 「어제」 / 「9/24」 | tabular-nums |
| 오른쪽 아래 | 상태 점(ok 없음 · failed 호박 · cancel 회색 ⏹) · 턴 수 알약(2턴 이상만) · 📌 | 상태는 면 물듦 + 작은 아이콘, 글자색 칠하지 않음 |
| 진행 중 | 면 accent α.10 + 오른쪽 **원형 ■ 정지(40dp)** | Muse 진행 중 카드 |
| 조작 | 탭 = 대화방 · 길게/⋮ = 이름 바꾸기 · 고정 · 보관 · 휴지통으로 · 왼쪽으로 밀기 = 휴지통(스낵바 「휴지통으로 옮겼어요 · 실행 취소」 5초) | 작업 중인 방은 보관·휴지통 비활성 |

- 휴지통 화면: 같은 줄 + 「남은 날 23일」 + 복원 / 「휴지통 비우기」(확인 대화상자: 「N개 대화와 실행 기록 M개를 영구 삭제합니다. 되돌릴 수 없어요」 — 빨강 주 버튼은 **「영구 삭제」 글자**로, 기본 포커스 없음).
- 빈 상태: 아이콘 + 「아직 시킨 일이 없어요. 측면 버튼을 길게 눌러 불러 보세요」 + 빌드 시각.

### 1-3. 대화방(채팅방) — 세션 한 개
- **머리**: 뒤로 · 제목(2줄, 탭하면 이름 바꾸기) · 메타 「오늘 오후 9:12 · 음성 · 2턴 · 41초」 · ⋮(고정·보관·휴지통).
- **본문**(`LazyColumn`, 열면 맨 아래):
  - 요청 = 오른쪽 말풍선(accent 바탕 α 또는 accent, 14/14/4/14), 아래 작게 출처 아이콘(🎙/⌨/팝업).
  - **단계 묶음** = 한 줄로 접힘 「단계 6개 · 앱 열기 · 탭 ×3 · 입력」(관제실 `rowsOf` 방식). 펼치면 시간순 단계 줄(도구 · 요약). 「자세히」 = runs jsonl 지연 로드(스크린샷 요약 포함) — Muse 「작업 계보」.
  - **확인 기록 카드** = 확인 게이트가 있었던 턴은 「확인 · 문자 보내기 → 실행함(9:13)」/「→ 거부(40초 무응답)」 점선 칩. 여기서 다시 실행하는 버튼은 없다(기록일 뿐).
  - 결과 = 왼쪽 에이전트 카드(테두리 말풍선 14/14/14/4, 왼쪽 작은 표식은 연속 턴에서 한 번만) + 상태 표시(⚠ 실패 사유 / ⏹ 멈춤) + 잘했어/틀렸어(평가 전만, 평가 후엔 작은 표시).
  - 진행 중 턴 = 단계 줄이 실시간으로 늘어남(`AgentService.listeners`의 `step`) + 끝에 ■ 정지.
- **아래**: `Composer`(이어서 시키기…). 이 방이 현재 방이 아니면 보내는 순간 이 방을 현재 방으로(작업 중이면 비활성).

### 1-4. 팝업 입력 카드(① — 다른 앱 위)
- 화면 **아래**에서 올라오는 카드(위 24dp 반경, 폭 `min(화면폭*0.94, 560dp)`, 아래 여백 = 내비 인셋 + 12dp). 카드 밖은 스크림 없이 원래 앱이 그대로 보인다(Gemini 방식). 카드 밖 탭 = 닫기.
- 구성(위→아래):
  1. 제안 칩 한 줄(왼쪽 정렬, 가로 스크롤): 「방금 한 일 이어서」(현재 방 제목) · 최근 요청 2개 · 「지금 화면에 대해」([결정] §7-8, 기본은 칩 없음).
  2. 입력줄: 작은 표식(원) · 입력칸 「무엇을 해 드릴까요?」(16sp, 여러 줄로 늘어남, 최대 4줄) · 🎙 · 원형 보내기(글 없으면 비활성).
  3. 한 줄 안내(11sp dim): 「측면 버튼을 다시 누르면 말하기 끝 · 기록 보기 ›」
- 음성: 카드가 뜨면 **자동 듣기**(설정 「열자마자 듣기」 계승). 듣는 중엔 입력칸 자리에 파형 막대 + 부분 결과 글자. 「다 말했다」 = 측면 버튼 한 번 더(세션 `onShow` 재호출) 또는 보내기.
- 보내기 순서(기술 §3-1 함정 1): 키보드 내림 → 카드 닫힘(`hide()`/`finish()`) → 창 목록에서 우리 창 사라짐 확인(최대 1초) → `startTask(text, {source:"popup"})`.
- 작업 중에 불렀을 때: 입력칸 대신 「작업 중 — 설정 › 디스플레이 여는 중」 + **■ 정지** + 「기록 보기」. v1 은 대기열 없음([결정] §7-9).
- 접근성 꺼짐: 「접근성 서비스를 먼저 켜 주세요」 + 설정 열기.

### 1-5. 상태 패널(⑤ — `Overlay.kt` 다듬기)
- 위치·창은 그대로(상단 가운데, y=36dp, 폭 `min(0.92w, 560dp)`, `TYPE_ACCESSIBILITY_OVERLAY`, 제스처 중 `setTouchable(false)` 순서 유지).
- 모양: 반경 18 → **20dp**, 바탕 토큰 `overlayBg`(먹색 α.92) + 1dp 테두리 `overlayLine`. 버튼은 기본 `Button` 대신 둥근 알약 TextView(최소 44dp 터치).
- 한 줄 구성: 왼쪽 **숨쉬는 점**(빛과 같은 주기, 8dp) · 상태 문장(14sp, 3줄, 펼치기 유지) · 단계 수·경과(11sp tabular) · 오른쪽 **정지**(알약, 붉은 테두리).
- **확인 카드**(ask 상태): 패널이 세로로 커진다 — ① 머리 「보내기 전에 확인할게요」(15 Bold) ② 본문 질문 그대로(`question`, 무엇을·누구에게·내용) ③ 남은 시간 막대 + 「32초 뒤 자동 취소」 ④ 버튼 둘 **[취소](넓게, 중립 면)** · **[실행](accent 테두리만, 채우지 않음)** — Muse 는 허용을 진하게 칠했지만, 우리는 음성·빠른 탭 오조작 위험이 커서 **거부 쪽을 기본값처럼** 보이게 한다. 정지 버튼 = 거부(현 동작).
- 평가 줄: 「잘했어 / 틀렸어」 알약 두 개(이모지는 글자 옆 보조).
- 텍스트는 지금처럼 `Plain.of`.

### 1-6. 테두리 빛(② — 새 `EdgeGlow.kt`)
| 상태 | 표현 | 켜는 곳 |
|---|---|---|
| 조작 중(ACT) | 붉은색 네 변, 알파 0.25↔0.90, **주기 3.6s**(반주기 1.8s, `PathInterpolator(0.4,0,0.2,1)`) | `startTask` 직후 |
| 생각·대기(THINK) | 같은 색, 진폭 축소 0.20↔0.45 | 모델 호출 중(선택 — P0 훅이 있으면) |
| 확인 대기(WAIT) | **숨쉬기 멈춤**, 확인색으로 α0.55 고정 + 시작 때 2회 느린 펄스 | `confirm()` 진입, 퇴장 시 ACT 복귀 |
| 끝·정지 | 450ms 페이드아웃 후 창 제거 | 에이전트 스레드 `finally` |
| 대화형 답만(도구 0) | 켜지 않거나 THINK 로만 | — |
| 캡처 순간 | 페이드 없이 즉시 INVISIBLE → 찍고 300ms 페이드인 | `setVisible(false/true)` 세 곳 |

- 그림: 네 변 `LinearGradient`(가장자리 `glow` α0.55 → 안쪽 0), 두께 **커버 22dp · 펼침 28dp**, 모서리는 `RoundedCorner` 반경(API31+, 없으면 24dp) + 두께의 `RadialGradient`. 가장자리 1dp 에 α0.8 선(시안에서 비교). 크기 바뀔 때만 셰이더 재생성. `hasOverlappingRendering()=false`, API35+ `setRequestedFrameRate(LOW)`.
- 창: `MATCH_PARENT`, `FLAG_NOT_TOUCHABLE|NOT_FOCUSABLE|LAYOUT_IN_SCREEN|LAYOUT_NO_LIMITS`, `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`, `setFitInsetsTypes(0)`, API33+ `setCanPlayMoveAnimation(false)`. **패널보다 먼저 add**(패널이 위) [추정 — 실기 확인]. `BadTokenException`·`IllegalStateException` 은 건너뜀(패널과 같은 패턴).
- `prefers-reduced-motion` 대응: 시스템 「애니메이션 제거」(`Settings.Global.ANIMATOR_DURATION_SCALE == 0`)면 α0.6 고정.

### 1-7. 설정
- 한 화면(커버) / 오른쪽 칸(펼침). 묶음: **부르기**(측면 버튼 설정 열기 `SIDE_KEY_SETTINGS` · 기본 디지털 어시스턴트 설정 열기 `Settings.ACTION_VOICE_INPUT_SETTINGS`/기본 앱 · 현재 상태 표시 「측면 버튼 길게: 폴드 에이전트 ✓」[미확인: 읽는 방법 — `Settings.Secure.assistant` 읽기]) · **말하기**(열자마자 듣기 · 답변 읽기) · **표시**(테마 3상태 · 빛 세기 약/중/강 · 빛 주기 느림/보통) · **기록**(휴지통 기간 30일 · 시험 기록 보기 · 전부 내보내기[후순위]) · **모델**(엔드포인트·모델·키 — 현 설정 계승) · 빌드 시각.
- 역할·측면 버튼 변경은 **앱이 대신 바꾸지 않는다** — 설정 화면을 열어 줄 뿐.

---

## 2. 폴드 자세 대응

| 자세 | 판정 | 홈/기록 | 대화방 | 팝업 카드 | 빛 |
|---|---|---|---|---|---|
| 커버 1248×1972px ≈ **475×751dp** | 폭 < 600dp | 한 화면씩: 홈 → 대화방(뒤로) | 전체 화면 | 폭 0.94w, 아래 | 두께 22dp, 커버 모서리 반경 |
| 펼침 2448×1848px ≈ **933×704dp** | 폭 ≥ 600dp | **2칸 `SplitPane`**: 목록 360dp \| 대화방(레일 끌기, 폭은 `list-unfold` 키로 저장) | 오른쪽 칸 | 폭 560dp 가운데 아래 | 두께 28dp |
| 반접힘(테이블톱·책) | 힌지 센서 | **v1 제외**(기록 앱 `Posture.kt` 이식은 2단계) | | | |

- 펼침 가로(폭 ≥ 840dp)에서도 v1 은 2칸(사이드바 3칸은 통계가 쌓인 뒤).
- 자세가 바뀌어도 선택 방·스크롤 유지: `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|uiMode"` + 상태는 뿌리 `remember`.
- 빛·팝업은 `onSizeChanged`/인셋 리스너로 재계산(`svc.resources.displayMetrics` 는 늦게 갱신될 수 있으니 쓰지 않는다).
- 펼침 화면 실기 시험은 **사용자가 펼쳐 줘야** 한다(§6).

---

## 3. 기술 결정

### 3-1. 팝업 호출 — 추천안과 대안
| 순위 | 방식 | 조건 | 장점 | 대가 |
|---|---|---|---|---|
| **추천** | (a) `VoiceInteractionService` + `VoiceInteractionSession` 자체 창(`TYPE_VOICE_INTERACTION`, trusted) | 사용자가 기본 디지털 어시스턴트를 폴드 에이전트로 + 측면 버튼 길게 = 디지털 어시스턴트 | 측면 버튼 직결 · 뒤 앱 resume 유지(Gemini 와 같은 모델)[추정] · 앱 이동 인상 없음 | 키보드·마이크 동작 [미확인] |
| 폴백 | (b) 같은 세션에서 `setUiEnabled(false)` + `startAssistantActivity(QuickAskActivity)` | (a)에서 키보드가 카드 위로 안 오거나 세션 중 음성이 안 될 때 | 일반 액티비티라 IME·마이크 확실 | 뒤 앱 onPause |
| 역할 안 바꿀 때 | (c) 투명 `QuickAskActivity` 를 두 번째 LAUNCHER(라벨 「에이전트 빠른 입력」)로 → 측면 버튼 「두 번 누르기 → 앱 열기」 | 역할 변경 거부 시 | 역할 변경 없음 | 삼성 목록이 액티비티 단위인지 [미확인] — 앱 단위면 `MainActivity`가 받아 곧바로 넘김(한 번 깜빡임) |
| 보조 | 퀵설정 타일 · 알림의 「부르기」 액션 | 언제나 | | 후순위 |

- **입력 UI는 View 하나(`QuickAskView`)** 로 만들어 세션 창·액티비티 어디에나 꽂는다. Compose 로 만들지 않는 이유: `VoiceInteractionSession` 은 LifecycleOwner 가 아니라 ComposeView 배선이 필요하고 선례가 없다(refapps §10-3-6). 색·반경은 §3-4 토큰을 읽어 Compose 화면과 같게 보인다.
- 매니페스트 선언·스텁 RecognitionService·`voice_interaction.xml` 은 tech §1-1 스니펫 그대로. `roles.xml` 상 ASSISTANT 는 `requestable=false` → 앱이 역할 요청 팝업을 띄울 수 없다. 사용자가 설정에서 고르거나, **사용자 승인 뒤** `cmd role add-role-holder`.
- `onHandleAssist`(화면 구조·스크린샷)는 v1 에서 **받지 않는다**([결정] §7-8).

### 3-2. 테두리 빛 구현 — §1-6. 핵심 결정 3개
1. 패널과 **별도 창**, `FLAG_NOT_TOUCHABLE` 고정(토글 경주 원천 제거 — `AgentService.gesture` 주석 run 172029-564).
2. 한 번 그리고 **`View.ALPHA` 애니메이션만**(RenderThread 합성만, 블러·RenderEffect 쓰지 않음).
3. 스크린샷 세 지점에서 패널과 함께 숨김. 150ms 대기로 부족하면 프레임 콜백 2회 뒤 캡처(구현 B 가 `IndicatorHooks.beforeCapture()` 안에서 처리).

### 3-3. Compose 전환 — 추천: **섞어 쓴다**
- Compose: `MainActivity`(홈·대화방·기록·설정). 참고 앱과 AGP 9.4.1·Gradle 9.7.1·compileSdk 36·minSdk 30 이 같아 검증된 조합(compose 플러그인 2.4.20, BOM **2026.06.01** — 2026.08+ 는 compileSdk 37 요구, activity-compose 1.12.4)을 그대로 쓴다 [확인: refapps §10-2].
- View 유지: `Overlay.kt`(패널) · `EdgeGlow.kt`(빛) · `QuickAskView`(팝업).
- 대가: APK debug 2.9MB → 약 30~35MB [추정: 참고 앱 debug 값], 첫 빌드 느려짐. 개인 사이드로드라 수용 가능. [결정] §7-5.
- 코루틴: Compose 가 들어오면 `kotlinx-coroutines` 가 따라온다(BOM 의존). RecordStore 관찰은 **콜백 리스너**로 정의해 View·Compose 양쪽에서 쓰고, D 가 Compose 쪽에서 `mutableStateOf` 로 감싼다(계약 §4-3).

### 3-4. 디자인 토큰(두 세계 공용)
- `ui/Tokens.kt`: `object FaTokens` — 라이트/다크 한 쌍씩 **ARGB Int**. View 는 그대로, Compose 는 `Color(int)`.
- 토큰 이름(관제실 `CorePalette` 10개 + 의미층): `bg, panel, panel2, raised, border, text, dim, accent, accentTint, onAccent` + `glow, glowWait, ok, warn(실패), stop(멈춤), overlayBg, overlayLine, overlayText`.
- 값은 [결정] §7-1·7-2 후 P0 가 채운다. 시안 1(Ember) 기준 초안은 §5.
- 글꼴: Pretendard(refapps §4) Regular·SemiBold 2개(≈3.2MB)를 `res/font` 에 — View 쪽은 `ResourcesCompat` 없이 `resources.getFont()`(API 26+).
- 반경: 5(태그) · 10(작은 면) · 14(카드·말풍선·입력) · 20(패널) · 24(팝업 카드 위). 1dp 테두리, 그림자 없음(빛만 예외).

### 3-5. 데이터 계층 — `research_records.md` §3 그대로 채택
- 세션 스키마 v2(하위 호환): `titleSource`·`summary`·`source`·`pinned`·`archived`·`trashedAt` + 턴 `title`·`source`·`ms`·`rating`.
- `files/records/index.json` 캐시(※ `sessions/` 안에 두면 `Session.all` 이 세션으로 읽는다), 휴지통 `sessions/.trash/`, 깨진 파일 `sessions/.corrupt/`, 영구 삭제 원장 `records/purged.jsonl`.
- 모든 쓰기 = 전역 락 안에서 「디스크 재읽기 → 변경 → 임시 파일 + rename」. 기존 결함(UI 에서 바꾼 제목이 실행 끝에 지워짐, 비원자적 쓰기)을 같이 고친다.
- 제목: 규칙 즉시(24자, 어절 경계) + LLM 비동기(첫 턴·3턴마다, adb 세션 제외, 8초 타임아웃, 실패 시 규칙값 유지). `titleSource=="user"` 는 절대 덮지 않음.
- 삭제: 보관 ⇄ 활성, 휴지통(30일) → 영구 삭제 시 runs 파일 + recipes 해당 run 줄 재작성 제거(bad 줄 방식 금지 — `analyze.py` 👎 통계 오염). learned.json 은 손대지 않음. 실행 중인 방은 보관·휴지통 불가. 현재 방을 지우면 `Prefs.setSessionId(newId())`.
- **이 앱 안의 기록 삭제는 사용자 조작**이라 에이전트 확인 게이트(`confirm`)와 별개. 게이트는 건드리지 않는다. 단 에이전트가 「기록 지워 줘」 같은 요청으로 이 기능을 부르는 도구는 v1 에 **만들지 않는다**.

---

## 4. ★구현 분할과 인터페이스 계약

### 4-0. 순서
```
P0 선행(한 작업자, 직렬, 커밋 1개) ──▶ A · B · C · D 병렬(각자 소유 파일만, 커밋 각자) ──▶ 통합 빌드(메인) ──▶ 실기(직렬, §6)
```
P0 이 끝나기 전에는 누구도 시작하지 않는다. P0 은 **동작을 바꾸지 않는** 추출·스텁·훅만 한다(빌드·설치 후 기존 기능 그대로여야 함).

### 4-1. 파일 소유권
| 파일 | P0 | A 팝업 | B 빛·패널 | C 기록 | D 화면 |
|---|---|---|---|---|---|
| `ui/Tokens.kt`(새) | **생성**(이름+초안값) | 읽기 | 읽기 | — | 읽기(값 조정은 D 가 P0 뒤 단독) |
| `VoiceInput.kt`(새, MainActivity 음성 추출) | **생성** | 사용 | — | — | 사용 |
| `RunIndicator.kt`(새, 인터페이스) | **생성** | — | 구현 | — | — |
| `EdgeGlow.kt`(새) | 스텁(no-op) | — | **소유** | — | — |
| `Overlay.kt` | — | — | **소유** | — | — |
| `AgentService.kt` | **훅 삽입**(이후 동결) | — | ✕ | ✕ | ✕ |
| `records/RecordStore.kt`(인터페이스·데이터 클래스) | **생성**(이후 동결) | 읽기 | — | 읽기 | 읽기 |
| `records/Records.kt`(싱글턴 접근자) | **생성**(초기값 = `LegacyRecordStore`) | — | — | **교체** | 사용 |
| `records/LegacyRecordStore.kt`(기존 Session 감싼 임시 구현) | **생성** | — | — | 삭제/대체 | — |
| `records/FileRecordStore.kt`·`TitleMaker.kt`·`Purge.kt`(새) | — | — | — | **소유** | — |
| `Session.kt` | — | — | — | **소유**(어댑터화) | — |
| `Agent.kt` | — | — | — | **363·266·374행 부근만** | — |
| `Knowledge.kt` | — | — | — | **recipes 재작성 함수 추가만** | — |
| `assist/*.kt`(FaVoiceService·FaSessionService·FaSession·FaRecognitionService) · `QuickAskActivity.kt` · `QuickAskView.kt`(새) | — | **소유** | — | — | — |
| `res/xml/voice_interaction.xml`(새) | — | **소유** | — | — | — |
| `AndroidManifest.xml` | `MainActivity` configChanges 만 | **소유**(서비스 3·액티비티 1·두 번째 LAUNCHER) | ✕ | ✕ | ✕ |
| `MainActivity.kt` | 음성 추출(동작 동일) | ✕ | ✕ | ✕ | **소유**(Compose 재작성) |
| `ui/**`(Compose 화면·부품·Theme) · `res/font/*` | — | — | — | — | **소유** |
| `app/build.gradle.kts` · 루트 `build.gradle.kts` | — | — | — | — | **소유**(Compose) |
| `Prefs.kt` | 새 키 전부 미리 추가 | 읽기 | 읽기 | 읽기 | 읽기 |
| `README.md`·인계 문서 | — | — | — | — | 메인 세션이 마지막에 |

✕ = 수정 금지. 필요하면 메인 세션에 요청(계약 변경은 메인 세션만).

### 4-2. P0 이 만드는 계약(시그니처 확정)

```kotlin
// ui/Tokens.kt
object FaTokens {
    data class Pal(val bg: Int, val panel: Int, val panel2: Int, val raised: Int, val border: Int,
                   val text: Int, val dim: Int, val accent: Int, val accentTint: Int, val onAccent: Int,
                   val glow: Int, val glowWait: Int, val ok: Int, val warn: Int, val stop: Int,
                   val overlayBg: Int, val overlayLine: Int, val overlayText: Int)
    val light: Pal; val dark: Pal
    fun of(ctx: Context): Pal          // Prefs.theme(system/light/dark) + uiMode 로 고름
    object R { const val tag = 5; const val small = 10; const val card = 14; const val panel = 20; const val sheet = 24 }  // dp
    const val GLOW_PERIOD_MS = 3600L
}

// VoiceInput.kt — MainActivity 의 인식 로직을 그대로 옮긴다(재사용 인식기 1개, 온디바이스 폴백, 무RMS 2초 판정, cue 진동 포함)
class VoiceInput(private val ctx: Context, private val cb: Callback) {
    interface Callback {
        fun onState(listening: Boolean, ready: Boolean)      // 준비 중 / 듣는 중
        fun onPartial(text: String)                         // 지금까지 전사(segments + partial)
        fun onFinal(text: String, alts: List<String>, conf: FloatArray?)
        fun onError(msg: String)
    }
    fun prewarm(); fun start(); fun finish(); fun cancel(quiet: Boolean = false); fun destroy()
    val active: Boolean
    fun meta(): JSONObject                                  // 지금 MainActivity 가 run() 에 넣는 음성 메타(후보·신뢰도) 그대로
}

// RunIndicator.kt — AgentService 가 부르는 표시 훅(메인 스레드에서 호출됨)
enum class RunPhase { ACT, THINK, WAIT }
interface RunIndicator {
    fun start()                      // 작업 시작
    fun phase(p: RunPhase)           // 확인 진입 WAIT / 퇴장 ACT / (선택) 모델 대기 THINK
    fun stop()                       // 작업 끝(성공·실패·정지 모두)
    fun setCaptureHidden(hidden: Boolean)   // 스크린샷 직전 true, 직후 false — 즉시 적용
    fun release()                    // onUnbind
}
// EdgeGlow.kt (P0 스텁)
class EdgeGlow(svc: AgentService) : RunIndicator { /* 전부 no-op — B 가 채운다 */ }
```

**AgentService.kt 에 P0 이 넣는 훅(이후 동결)**
- `private var glow: RunIndicator? = null` — `onServiceConnected` 에서 `glow?.release(); glow = EdgeGlow(this)`, `onUnbind` 에서 `release()`.
- `startTask`: `running = agent` 직후 `main.post { glow?.start() }`; `finally` 에서 `main.post { glow?.stop() }`.
- `confirm`: ask 를 띄우는 `main.post` 안에서 `glow?.phase(WAIT)`, `clearAsk` 옆에서 `glow?.phase(ACT)`. **40초·반환값 로직은 한 글자도 바꾸지 않는다.**
- 캡처 세 곳(`screenshotJpegBase64`·`captureBitmap`·`systemScreenshot`): 기존 `overlay?.setVisible(false/true)` 옆에 `glow?.setCaptureHidden(true/false)`.
- `@Volatile var runningSessionId: String? = null` 노출(C·D 가 「실행 중인 방」 판정에 씀), `startTask` 에서 설정·`finally` 에서 null.
- `var lastStatus: String = ""` + `status()` 에서 갱신(D 상태 카드·A 팝업의 「작업 중 — …」 문구용), `stepCount`·`startedAt` 도 같이.
- 평가 콜백: `overlay?.rate { good -> Thread { agent.feedback(good); Records.store.rate(agent.runId, good) }.start() }` — `agent.runId` 가 없으면 P0 이 `Agent` 에 `val runId` 공개 getter 만 추가(Agent.kt 에서 P0 이 건드리는 유일한 곳).
- 세션 선택: `startTask` 의 `Session.load/current` 는 그대로 둔다(C 가 Session 을 어댑터로 바꾸므로 호출부 불변).

**Prefs 새 키(P0)**: `theme`(system/light/dark), `glowStrength`(0.6/0.8/1.0), `glowPeriodMs`, `trashDays`(30), `showTests`(false), `llmTitles`(true), `popupAutoListen`(true).

### 4-3. 기록 계약 — `records/RecordStore.kt`(P0 생성, 동결)
`research_records.md` §3.6 을 거의 그대로, 코루틴 의존 없이:
```kotlin
data class RecordSummary(val id: String, val title: String, val titleSource: String, val summary: String,
    val source: String, val pinned: Boolean, val archived: Boolean, val trashedAt: Long,
    val created: Long, val updated: Long, val turns: Int, val lastStatus: String, val lastRequest: String, val lastTool: String)
data class Step(val tool: String, val summary: String)
data class TurnCard(val run: String, val title: String, val request: String, val final: String, val status: String,
    val t: Long, val ms: Long, val source: String, val rating: Boolean?, val steps: List<Step>, val confirms: List<ConfirmMark>)
data class ConfirmMark(val question: String, val result: String /* ok|denied|timeout */, val t: Long)   // runs jsonl 의 confirm 이벤트에서
enum class Shelf { ACTIVE, ARCHIVED, TRASH }
data class RecordFilter(val shelf: Shelf = Shelf.ACTIVE, val includeTests: Boolean = false, val query: String = "",
    val status: String? = null, val pinnedOnly: Boolean = false, val since: Long = 0)
sealed interface RecordEvent { data class Changed(val id: String): RecordEvent; data class Removed(val id: String, val toTrash: Boolean): RecordEvent
    data class Restored(val id: String): RecordEvent; data class Purged(val ids: List<String>, val runs: Int): RecordEvent; object Rebuilt: RecordEvent }

interface RecordStore {
    fun list(filter: RecordFilter = RecordFilter()): List<RecordSummary>   // pinned 먼저, updated 내림차순. IO 스레드에서 부를 것
    fun get(id: String): List<TurnCard>?
    fun trace(runId: String): File?
    fun currentId(): String                                                // Prefs.sessionId (없으면 새 id)
    fun select(id: String)                                                 // 이어 갈 방 바꾸기(작업 중이면 IllegalStateException)
    fun startNew(): String
    fun appendTurn(id: String, turn: JSONObject, source: String)
    fun historyMessages(id: String): List<JSONObject>
    fun rate(runId: String, good: Boolean)
    fun applyGenerated(id: String, title: String?, summary: String?, turnRun: String?, turnTitle: String?)
    fun rename(id: String, title: String); fun pin(id: String, on: Boolean); fun archive(id: String, on: Boolean)
    fun delete(id: String); fun restore(id: String): String; fun purge(ids: List<String>? = null, olderThanDays: Int = 30): Int
    fun addListener(l: (RecordEvent) -> Unit); fun removeListener(l: (RecordEvent) -> Unit)   // 메인 스레드로 전달
    fun rebuildIndex()
}
object Records { @Volatile var store: RecordStore = ... ; fun init(ctx: Context) }   // Application 없이: AgentService·MainActivity 첫 사용 때 init
```
- **D 는 이 인터페이스만 쓴다.** D 는 자기 시험용 `FakeRecordStore`(ui 패키지 안, 예시 데이터)를 둘 수 있다. C 의 내부 구현(인덱스·락·파일 배치)에 의존하지 않는다.
- P0 의 `LegacyRecordStore` 는 기존 `Session` 으로 list/get/currentId/select/startNew 만 동작, 나머지는 no-op — 병렬 기간에 D 가 실데이터로 화면을 볼 수 있게.
- C 가 끝나면 `Records.store = FileRecordStore(ctx)` 로 교체하고 `Session` 은 RecordStore 를 감싼 어댑터(`Agent` 쪽 호출 363·266 행만 수정).

### 4-4. 팝업 계약(A)
- `QuickAskView(ctx: Context, host: Host) : FrameLayout` — `interface Host { fun dismiss(); fun submit(text: String, meta: JSONObject) }`. 세션 창과 `QuickAskActivity` 가 각각 Host 를 구현.
- 제출 경로: `host.dismiss()` → IME 내림 → `AgentService.instance.windows` 에서 우리 창이 사라질 때까지 폴링(100ms×10) → `AgentService.instance?.startTask(text, meta.put("source","popup"))`. 서비스가 없거나 busy 면 카드 안에 이유 표시(닫지 않음).
- 새 입구 가드: `QuickAskActivity` 는 `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`·`savedInstanceState != null` 이면 아무것도 제출하지 않는다. `excludeFromRecents`·`noHistory`·`taskAffinity="kr.joonlab.foldagent.ask"`.
- 음성은 `VoiceInput` 으로. 측면 버튼 재누름(세션 `onShow` 재호출 / 액티비티 `onNewIntent`) = `VoiceInput.finish()`.
- A 는 `MainActivity`·`AgentService` 를 건드리지 않는다.

### 4-5. 빛·패널 계약(B)
- `EdgeGlow : RunIndicator` 구현(§1-6), `Overlay` 공개 함수 시그니처(`show/hide/ask/clearAsk/rate/setVisible/setTouchable/refreshButton`) **유지** — 모양만 바꾼다. 확인 카드 남은 시간 표시는 `ask()` 시점부터 Overlay 가 자체 40초 카운트다운을 그린다(판정은 AgentService 쪽 그대로, 표시가 먼저 0 이 돼도 판정 불변).
- 숨쉬는 점(패널)은 `FaTokens.GLOW_PERIOD_MS` 공유.

### 4-6. 화면 계약(D)
- `MainActivity.handle()` 의 **디버그·adb 인텐트 분기 전부(cmd·session·cancel·exportapps·dumplabels·pastetest·confirmtest·showChat·replay 가드)를 그대로 옮긴다** — `dev.sh`·스킬이 이것에 의존한다. `run()` 뒤 `moveTaskToBack(true)` 동작도 유지.
- 진행 중 표시: `AgentService.listeners`(`step`/`finish`) + `runningSessionId`·`lastStatus`.
- 확인 게이트 UI 는 D 화면에 두지 않는다(오버레이 한 곳).

---

## 5. 시안 3방향(상세는 `mockups.html`)

| | 1. 숨결 Ember(추천) | 2. 맑은 창 Clear | 3. 관제 Console |
|---|---|---|---|
| 인상 | 따뜻한 먹색 위 잉걸불. 빛이 주인공, 나머지는 고요 | 밝고 둥글고 친근. Muse 의 큰 카드·아바타 상태 한 줄 | 정보 밀도 높은 운영 화면. cmux 관제실 계보 |
| 바탕/본문 | `#15120F` / `#EDE6DF` (라이트 `#FAF7F4`/`#221C18`) | `#F3F4F7` / `#15171C` | `#F7F8F8` / `#1B1F1E`(다크 짝 `#0E1110`) |
| 키 색 | **`#E5484D`** ember | 강조 `#2F5BEA` 코발트 + 빛 `#F0444A` | `#C8323C` 크림슨 + 상태색(호박 `#B7791F`) |
| 빛 | 가장자리 α.55, 22dp, 1dp 선 없음 | α.45, 28dp 넓고 옅게 | α.6, 14dp 얇고 선명 + 1dp 선 |
| 글꼴 | Pretendard(시안은 Gothic A1) | Pretendard(시안은 Gowun Dodum 제목) | Pretendard + 모노(시안은 IBM Plex Sans KR·Mono) |
| 모양 | 반경 14/20/24, 테두리 없는 면 | 반경 20/28, 흰 카드 | 반경 8/10, 1dp 테두리, 왼쪽 3dp 상태 띠 |
| 근거 | 요구 ②(붉은 빛) · refapps 원칙 3(평평함+빛 예외) · 관제실 다크 | Muse 활동 화면·결제 카드 · Gemini 하단 카드 | 관제실 상태 물듦·요약 줄 · 기록 앱 목록 줄 |
| 위험 | 붉은색이 「오류」로 읽힐 수 있음 → 실패는 호박색 | 붉은 빛과 파란 강조가 경쟁 | 딱딱함, 일상 앱으로는 차가움 |

---

## 6. 실기 검증 계획(메인 세션, 폰 하나 = 직렬)

**매 시험 전**: `adb logcat -d -v time -b events | grep input_interaction | tail -1` 이 내 마지막 조작보다 뒤면 사용자가 쓰는 중 → 기다린다. 스크린샷은 `adb shell screencap -p /data/local/tmp/fa_<항목>.png` → pull. 키보드가 떠 있으면(`dumpsys input_method | grep mInputShown`) 스와이프 금지.
**금지**: `am force-stop kr.joonlab.foldagent` · `adb logcat -c` · **실행 중 `uiautomator dump`**(창 확인은 `dumpsys window windows | grep -i foldagent`·런 기록으로) · 폰 펼치기·잠금 해제·로그인·측면 버튼 누르기는 **사용자에게 요청**.

| # | 단계 | 판정(스크린샷/기록) | 통과 기준 |
|---|---|---|---|
| V0 | P0 뒤 회귀 | `./dev.sh ask` 기존 요청 1건 + confirmtest | 동작·확인 카드 동일, 40초 거부 로그 `confirm「…」 → timeout` |
| V1 | 빛 켜짐(B) | 작업 중 캡처 2장(0.9s 간격) | 네 변 붉은 빛, 두 장의 밝기 차 보임, 모서리 곡률 맞음 |
| V2 | 빛이 탭을 막지 않음 | 좌표 탭 요청 + `filterTouchesWhenObscured` 화면(권한 대화상자 등) | 탭 반영, `logcat -d` 에 `Untrusted touch due to occlusion` 없음 |
| V3 | look/capture 에 빛 안 찍힘 | 실행 기록의 캡처 JPEG 가장자리 16px 평균 R−G | 빛 없는 기준과 차이 < 임계 |
| V4 | 확인 대기 표현 | confirmtest 중 캡처 | 숨쉬기 멈춤·확인색·패널 확인 카드(취소 넓게, 남은 초) · 무응답 40초 → 거부 로그 |
| V5 | 정지 | 작업 중 패널 정지 · 목록 ■ · 팝업 ■ 각각 | 3곳 모두 `cancel`, 빛 페이드아웃 |
| V6 | 역할 변경(**사용자 승인 후**) | `dumpsys voiceinteraction` 현재 interactor | 우리 VIS |
| V7 | 측면 버튼 길게(**사용자가 누름**) | 캡처 | 원래 앱이 그대로 보이고 하단 카드 |
| V8 | 팝업 키보드 | 입력칸 탭 후 캡처 | 키보드가 카드 아래, 카드 안 가려짐 → 실패 시 폴백(b) 전환 |
| V9 | 팝업 음성 | 로그 `onReadyForSpeech`/부분 결과 | 카드에 부분 결과 표시 |
| V10 | 팝업 → 작업 시작 순서 | 런 기록 첫 화면 | 첫 화면이 원래 앱, 우리 입력창 텍스트 없음 |
| V11 | 기록 목록 | 홈 캡처(커버) | 제목·설명·시각·상태·고정 표시, 시험 세션 숨김 |
| V12 | 휴지통·복원·영구 삭제 | 시험용 세션(`--es session qa-del`)만 대상 | 파일 이동·복원 id·runs/recipes 줄 제거·purged.jsonl — **사용자 실데이터로 삭제 시험 금지** |
| V13 | 제목 보존 경합 | 실행 중 그 방 이름 바꾸기 → 끝난 뒤 | 바꾼 제목 유지 |
| V14 | 펼침(**사용자가 펼침**) | 홈 2칸·빛 28dp·팝업 560dp 캡처 | 레일·칸 배치 정상 |
| V15 | 역할 되돌리기(사용자 결정 시) | 역할 해제 | 측면 버튼 → Gemini |
| V16 | 배터리/주사율 | 빛 5분, `dumpsys gfxinfo kr.joonlab.foldagent` | 프레임 드롭 없음 |

변경 단위마다 커밋(P0 / A / B / C / D / 통합 수정). 판정은 모델 말이 아니라 캡처로.

---

## 7. 사용자에게 물을 결정(AskUserQuestion 후보 — 하나씩, 반대 근거 병기)

| # | 결정 | 추천 | 반대 근거 / 대안 |
|---|---|---|---|
| 7-1 | 디자인 방향 | **1. 숨결 Ember**(다크 기본 + 라이트 짝) | Clear 가 낮 사용·친근함엔 낫다. Console 은 관제실과 일관되지만 일상 앱엔 차갑다 |
| 7-2 | 강조색 | **`#E5484D` 하나로 브랜드+빛**, 실패는 호박색 | 붉은색=오류 연상. 대안: 브랜드는 중립(먹색·흰색)으로 두고 **빨강은 빛·정지에만** / 기록 앱 진홍(`#A50034`)와는 겹치지 않게 |
| 7-3 | 기본 디지털 어시스턴트를 폴드 에이전트로 | **바꾼다**(시험 기간 후 유지 여부 재결정) | 측면 버튼 길게=Gemini 를 잃는다(설정에서 되돌리면 복귀). 역할 보유자 권한 세트(SMS 등) 부여 여부 [미확인] — 설치 후 `dumpsys package` 로 확인 |
| 7-4 | 측면 버튼 설정 | **길게 = 디지털 어시스턴트(우리)**, 두 번 누르기 = 현행 유지 | 역할을 안 바꾸면: 두 번 누르기 → 「에이전트 빠른 입력」(투명 팝업, 뒤 앱 onPause) |
| 7-5 | Compose 전환 | **앱 화면만 Compose**, 오버레이·팝업은 View | APK 2.9→~30MB, 빌드 느림. 대안: 전부 View 로 참고 앱 부품을 다시 짬(손 많이 감) |
| 7-6 | 확인 대기 중 빛 | **숨쉬기 멈추고 호박색 고정**(다른 신호) | 붉은색 유지 + 느린 2회 펄스 후 고정 / 빛 끄고 패널만 |
| 7-7 | 빛 주기·세기 | **3.6초 주기, 가장자리 α.55, 커버 22dp** | 2.8초(더 살아 있음, 거슬릴 수 있음) · 4.4초(더 차분). 시안의 주기 선택기로 비교 |
| 7-8 | 팝업이 현재 화면을 맥락으로 넘길지 | **넘기지 않음**(에이전트가 시작하면 어차피 읽는다) | 「이 화면에 대해」 칩을 눌렀을 때만 넘기는 절충 |
| 7-9 | 작업 중 팝업에서 새 요청 | **v1 은 정지만**(대기열 없음) | Muse 처럼 대기열 — 구현·안전 판정 복잡 |
| 7-10 | 영구 삭제 시 성공 경로(recipes)도 지울지 | **같이 지움**(요청 문구가 남기 때문) | 학습 유지 |
| 7-11 | 휴지통 30일 · 시험 기록 기본 숨김 · LLM 제목 사용 | **예 · 예 · 사용자 방만** | 프록시 호출 증가 |
| 7-12 | 에이전트 얼굴 | **작은 원형 표식만**(이름·아바타 없음) | Muse 식 아바타·이름 — 개성은 생기나 과함 |
| 7-13 | 글꼴 | **Pretendard 2굵기 번들(≈3.2MB)** | 시스템 글꼴(삼성 One UI) — 0MB, 참고 앱과 인상 다름 |

---

## 8. 미확인 목록(실기에서 판정)
- 삼성이 측면 버튼 길게에서 VIS 세션을 여는지(ASSIST 액티비티가 아니라) · 세션 창 키보드·마이크 동작 · 빛 창이 패널 아래에 오는지(add 순서) · 삼성이 trusted overlay 가림 판정을 바꿨는지 · `setRequestedFrameRate` 반영 · 역할 보유 시 추가 권한 · 삼성 「앱 열기」 목록이 액티비티 단위인지 · Compose release APK 크기 · Muse 모션 사양(주기·이징)은 공개 자료에 없음(`research_muse.md` §2-7).

---

## 9. 확정 결정 (2026-09-26 사용자)

| # | 결정 | 확정 |
|---|---|---|
| 7-1 | 디자인 방향 | **1. 숨결 Ember** (다크 기본 + 라이트 짝) |
| 7-3·7-4 | 팝업 호출 | **기본 어시스턴트·측면 버튼은 바꾸지 않는다.** 대신 Good Lock **Home Up 「멀티핑거 제스처」 3손가락 「두 번 탭」 → 「앱 실행」 → 「에이전트 빠른 입력」**(지금은 「화면 끄기」). 즉 §3-1 의 (c) 투명 `QuickAskActivity` 를 **두 번째 LAUNCHER 항목(라벨 「에이전트 빠른 입력」)** 으로 만드는 것이 v1 의 정본 입구. VoiceInteractionService(a)·(b)는 **v1 에서 만들지 않는다**(Home Up 액션 목록의 「어시스턴트 앱」도 나중 선택지로 남긴다). 제스처 설정은 사용자가 폰에서 한다. |
| 7-5 | Compose | **앱 화면만 Compose**, 오버레이·빛·팝업은 View |
| 7-6 | 확인 대기 빛 | **숨쉬기 멈추고 호박색 고정** |
| 그 밖 | 7-2·7-7~7-13 | DESIGN 추천값 그대로(키 색 `#E5484D`·실패 호박, 3.6초·α.55·22dp, 화면 맥락 안 넘김, 작업 중 팝업=정지만, 영구 삭제 시 recipes 도 제거, 휴지통 30일·시험 기록 숨김·LLM 제목, 작은 원형 표식, Pretendard 2굵기) |

작업 A 범위 조정: `assist/*`·`voice_interaction.xml` 없음. `QuickAskActivity`(투명, 아래 카드, `excludeFromRecents`·`taskAffinity=kr.joonlab.foldagent.ask`·`launchMode=singleInstance` 류로 재호출 시 onNewIntent)+`QuickAskView`+매니페스트 두 번째 LAUNCHER(아이콘 구별). 3손가락 두 번 탭을 다시 하면 = 「다 말했다」(onNewIntent). 카드 밖 탭·뒤로 = 닫기.
