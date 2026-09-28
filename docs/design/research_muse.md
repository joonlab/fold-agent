# 벤치마킹 조사 — Meta「Muse」 + 화면 가장자리 빛(Gemini·Galaxy·Siri)

- 조사일: 2026-09-26 · 도구: WebSearch(URL 확보) → cmux browser(본문·이미지 확인)
- 표기: **[확인]** = 원문을 cmux browser 로 직접 읽음 · **[검색요약]** = 검색 결과 요약만 봄(원문 미열람) · **미확인** = 근거 없음
- UI 참고 이미지(공식 디자인 글에서 캡처) — 타사 저작물이라 공개 저장소에는 넣지 않았다. 아래 파일 이름은 조사 당시 기록
  - `muse_status_activity.png` — 아바타 아래 상태 한 줄 + 오늘 작업 기록 목록
  - `muse_goals.png` — Goals 탭(Tracking / Goals 두 묶음)
  - `muse_checkout_approval.png` — 결제 승인 카드(Deny / Allow)
  - `muse_design_page.png` — 공식 디자인 글 화면(로고·타이포)

---

## 1. Muse 는 정확히 무엇인가

**결론: Meta 가 2026-09-08 출시한 "개인 AI 에이전트" 앱(서비스)이다.** 챗봇이 아니라 실제로 일을 대신 하는 에이전트. [확인]

| 항목 | 내용 | 근거 |
|---|---|---|
| 출시 | 2026-09-08, 미국(이후 캐나다·멕시코) · iOS·Android·웹 muse.ai, WhatsApp 에서도 대화 | Meta 뉴스룸 [확인] |
| 정체 | 개인 AI 에이전트. 메일 발송·여행 예약·양식 작성·쇼핑·목표 관리 | Meta 뉴스룸 [확인] |
| 모델 | **Muse Spark** (Meta Superintelligence Labs 첫 모델, 2026-04-08 발표) | 뉴스룸 관련기사 링크 [확인] / TechCrunch [확인] |
| 실행 환경 | **Muse Secure VM** — 사용자별 클라우드 가상 컴퓨터(자체 브라우저·파일시스템·터미널). 별도 **Sentinel 에이전트**가 외부로 나가는 행동을 승인 | 뉴스룸·디자인 글 [확인] |
| 가격 | 무료 + 월 $20 / $100 구독 | 검색 결과(tech-insider 등) [검색요약] |
| 반응 | 미국 App Store 무료 1위, 다운로드 250만(CNN, 9/23)~340만(Fox Business) | CNN [확인] / Fox [검색요약] |
| Connect 2026(9/23~24) 추가 발표 | 실시간 음성(말하는 동안 뒤에서 일함)·목소리 디자인, **Muse Realtime Avatar**(아바타 영상통화), AI 안경 연동(이름 부르면 호출, 보고 있는 것에 대해 행동), 전용 이메일 주소, **Mac 컴퓨터 사용**(허락하면 Mac 의 모든 앱 조작), 커넥터 확대(Walmart·Best Buy·Notion·GitHub 등), **Muse Charm**(주머니 크기 전용 기기, 연말 추가 공개) | Meta 블로그·TechCrunch [확인] |

### 같은 이름 구분
- **Muse Spark** — Meta 의 모델 이름(2026-04). 앱 Muse 를 구동하는 엔진. 앱과 혼동 주의.
- **Muse Charm** — Muse 전용 휴대 기기(키링형, "라부부+다마고치" 같다는 평). [확인: Meta 블로그·NBC]
- **Muse Realtime Avatar / Realtime Voice** — Muse 의 하위 모델·기능.
- Meta 가 아닌 동명: Interaxon 의 뇌파 헤드밴드 "Muse"(위키백과 검색결과에 등장), 록밴드 Muse. Microsoft 의 게임 생성 모델 "Muse"(2025)도 있으나 이번 조사에서 원문은 열지 않음 — 미확인.
- 이 외에 **Meta 의 과거 "Muse"** 제품이 있었는지는 이번 검색에서 나오지 않음 — 미확인.
- **사용자가 말한 "최근 Meta Muse" = 2026-09-08 출시 개인 에이전트 앱 Muse** 로 특정한다.

---

## 2. Muse 의 UI/UX (공식 디자인 글 "How We Designed Muse" + 리뷰)

출처: https://introducing.muse.ai/ (Mona Sarantakos·Christine Awad, 2026-09) [확인], CNN 리뷰 [확인], Lenny's Newsletter 리뷰 목차 [확인]

### 2-1. 호출·입력 방식
- **메신저처럼 말 걸기.** 앱 안의 "하나의 긴 메인 대화"가 기본. 턴제가 아니다 — 대답을 기다리지 않고 **여러 일을 연달아 보내거나 끼어들 수 있다.** [확인]
- 주제별로 맥락을 나누고 싶을 때만 **사이드 채팅(side chats)**. [확인]
- 호출 경로: Muse 앱 · WhatsApp · 웹 · Mac 앱 · (예정) 안경에서 에이전트 이름을 부르면 호출 · Muse Charm. [확인]
- **안드로이드에서 "측면 버튼 길게 → 화면 위 팝업 입력" 같은 시스템 오버레이 호출 방식은 발견하지 못함 — 미확인.** Muse 는 앱 안 대화 중심이다. 팝업 입력의 벤치마크는 Gemini 오버레이(아래 4절)가 맞다.

### 2-2. 대화 표시
- **채팅 말풍선.** 이유를 명시: 에이전트가 먼저 말을 걸고(proactive) 사용자가 여러 개를 연달아 보내니, 경계 없는 평문 기록은 읽기 어렵다 → "말풍선이 한 생각의 끝과 다음 생각의 시작을 표시한다". [확인]
- 먼저 말 거는 메시지의 문턱을 높게 둔다: "진짜로 도움이 되고 방해할 가치가 있을 때만", 사용자가 빈도를 조절. [확인]
- 백그라운드 작업이 끝나면 결과가 보여줄 만한지 스스로 판단하고, **새롭거나 입력이 필요할 때만 알린다.** [확인]

### 2-3. 에이전트가 일할 때의 시각 표시 (가장 중요)
- **아바타 + 상태 한 줄.** 아바타 아래에 지금 하는 일의 짧은 조각(예: 코트 아이콘 + "Checking price")을 띄운다. [확인, `muse_status_activity.png`]
- CNN 체험: "화면 상단에서 픽사풍 아바타가 노트북을 두드리며 일하고 있음을 알려 줬다". [확인]
- 브라우저 작업은 클라우드 VM 에서 돌며, **사용자가 탐색 과정을 볼 수 있고 언제든 끼어들 수 있다.** [확인: CNN]
- **화면 테두리 빛(edge glow) 같은 전체화면 표시는 Muse 자료에서 발견하지 못함 — 미확인.** (Muse 는 사용자 폰 화면을 직접 조작하지 않고 클라우드 VM 에서 일하므로 구조상 필요가 적다. Mac 컴퓨터 사용 시 표시 방식은 미확인.)

### 2-4. 작업 기록(활동 로그)
- 아바타를 탭하면 **전체 활동 로그 + 승인해 둔 권한** 목록이 나온다. [확인]
- 활동 화면 구조(캡처 기준): 상단 원형 아바타(연필 편집 뱃지) → 이름(굵게, 큰 글씨) → 회색 상태 한 줄 → **알약형 세그먼트 탭 4개(목록 / 방패=권한 / 시계=일정 / 지문=보안 추정 — 아이콘 의미는 추정)** → "Today" 날짜 머리글 → **카드 목록**: 왼쪽 둥근 사각 아이콘(도구 종류: 지구+돋보기=웹 검색 등), **제목(굵게) · 한 줄 설명(회색) · 시각(9:19 pm)**, **진행 중인 항목만 옅은 회색 배경으로 강조 + 오른쪽 원형 정지(■) 버튼**. [확인, 이미지]
- Lenny's 리뷰: "활동 피드와 작업 계보(task lineage) — 도구 호출과 단계별 계보", "Codex·Claude Code 에도 있었으면 한 기능". [확인: 목차]
- 투명성 원칙: "볼 수 없는 것은 믿을 수 없다(You can't trust what you can't see)". 기억 파일(Memory)도 사용자가 직접 읽고 고칠 수 있다. 뉴스룸: "한 일과 할 일의 완전한 감사 기록(audit trail)". [확인]

### 2-5. 목표·산출물·아이디어
- **Goals 탭**: "Tracking"(초록 점 + 초록 글씨) / "Goals"(파랑 점 + 파랑 글씨) 두 묶음, 각 항목은 체크박스 + 굵은 제목 + 회색 부제(진행 상황, 예: "Your Sep 7-13 draft is ready for approval") + 오른쪽 ⋮ 메뉴, "Show 8 more" 접기. [확인, 이미지]
- **Artifacts**: 긴 글 대신 일정표·대시보드·PDF·웹페이지 같은 풍부한 산출물을 대화로 보내고 대화 밖에서도 보관. [확인]
- **Ideas 탭**: 무엇을 시킬지 모르는 문제 해결용 프롬프트 제안. 앱은 탭 5개(메인 채팅 / 피드 / Ideas / Goals / 파일·미디어). [확인: CNN]

### 2-6. 확인 게이트(결정론 UI)
- "대화만으로는 부족한 곳"에 **결정론 UI 를 양보 불가로 둔다**: 구조화된 **승인 카드(수락/거절)**, 자격증명 보안 저장소. [확인]
- **배너 맹목(banner blindness) 경계** — 다 눌러 치우는 습관을 막기 위해, 기본값은 일반 탐색은 그냥 하고 **되돌리기 어려운 행동(메일 쓰기·구매)에서만 멈춘다.** [확인]
- 결제 승인 카드(캡처): 카트 아이콘 + "Checkout · Veda wants to place an order at …" 굵은 제목 → 회색 경고문("상인 사이트에서 세부·약관을 꼼꼼히 확인") → **실제 페이지 미리보기 썸네일**(+ "Review order" 확대 칩) → 결제수단 행(카드 •••• 1234, Pay with Link) → "Estimated total $80" → 하단 버튼 2개 **Deny(회색 알약) / Allow(진한 파랑 알약)**. [확인, 이미지]

### 2-7. 시각 언어
- 로고: 파란 손글씨 "m" 스크립트. 웹 CTA "Try Muse" = 밝은 파랑 알약 버튼. 디자인 글은 어두운 배경(#1a1a1a 근처) + 흰 산세리프. [확인, 캡처]
- 앱 화면(캡처)은 **밝은 회백 배경, 큰 모서리 둥근 카드(약 24~32dp 상당), 굵은 제목 + 회색 부제, 얇은 선 아이콘, 강조색 파랑(#0064E0 근처)·초록(Tracking)**. 색 코드는 캡처에서 눈대중 — 정확한 값 미확인.
- **아바타/개성**이 핵심 기능: 이름·외형·말투를 사용자가 정함. 기본 아바타 "Jolly"(크림색, 까만 점눈, 웃는 입). 저커버그의 아바타 "Agrippa"(토가+월계관). [확인: NBC·TechCrunch]
- 모션: 아바타가 일하는 애니메이션(CNN) 외 구체적 모션 사양(주기·이징)은 **미확인**.

---

## 3. 폴드 에이전트에 가져올 디자인 요소 (제안 8개)

1. **작업 카드 = 아이콘 · 제목(굵게) · 한 줄 설명(회색) · 시각** — Muse 활동 목록 형식 그대로. 요구 ③(제목·설명·일시)에 정확히 맞는다. 날짜 머리글("오늘 / 어제 / 9월 24일")로 묶는다.
2. **진행 중 카드만 옅은 배경 강조 + 오른쪽 원형 ■ 정지 버튼.** 실행 중 작업을 목록 안에서 바로 멈출 수 있게. 상단 패널의 정지와 같은 동작으로 통일.
3. **"지금 하는 일" 한 줄 상태** — 상단 패널과 대시보드 헤더에 아이콘 + 짧은 동사구("설정 앱 여는 중"). 테두리 빛(요구 ②)과 짝을 이루는 텍스트 신호.
4. **메인 대화는 말풍선, 연달아 보내기 허용** — 에이전트가 일하는 중에도 입력창을 막지 않고 다음 지시를 큐에 넣는다(Muse 의 비턴제 원칙). 주제별 분리가 필요하면 "사이드 채팅" = 기존 대화 세션.
5. **작업 계보(lineage) 펼치기** — 카드를 탭하면 단계별 도구 호출(탭·스와이프·입력·스크린샷)이 시간순으로 펼쳐진다. 실행 기록(run log)과 1:1 연결 → 정합성 유지.
6. **확인 게이트는 결정론 카드로, 되돌릴 수 없는 행동에서만** — 발송·결제·삭제는 Muse 결제 카드처럼 "무엇을 · 어디서 · 대상 미리보기(스크린샷 썸네일) · 거절(회색)/허용(강조색)" 구조. 게이트를 약하게 만들지 않되, 일반 탐색엔 묻지 않아 배너 맹목을 막는다.
7. **Tracking / 완료 두 묶음 + "N개 더 보기" 접기** — 기록 목록이 길어져도 깔끔. 항목별 ⋮ 메뉴에 보관·삭제.
8. **에이전트에 이름·얼굴(작은 아바타 또는 표식)** — 과하지 않게, 상단 패널·팝업 입력창 왼쪽에 작은 원형 표식. "누가 내 폰을 만지는가"를 인격으로 표시(선택 사항, 결정 필요).
9. (보조) **먼저 알리는 기준을 높게** — 백그라운드 작업 완료 시 "새로운 것이 있거나 입력이 필요할 때만" 알림.

---

## 4. 비교: 팝업 입력 + 화면 가장자리 빛

### 4-1. Google Gemini (안드로이드 오버레이)
- **호출**: 전원(측면) 버튼 길게, "Hey Google", 하단 모서리에서 대각 스와이프 → **화면 하단에 작은 오버레이**. [확인: 9to5Google 2025-01-24]
- **입력창 구성**(2025-01 개편): ＋ 메뉴(카메라·갤러리) · "Ask Gemini" 텍스트 필드 · 마이크 · Gemini Live 바로가기 한 줄. 받아쓰기가 길어지면 필드가 세로로 늘어난다. 위쪽에 **"Ask about screen / PDF / YouTube" 제안 칩**(왼쪽 정렬). 오버레이는 둥근 사각, 앱 안은 알약형. [확인]
- **빛**: 2025-01 당시 오버레이 둘레 **파랑/보라** 빛, 마이크 활성 시 빛 고리. [확인] → 2025-08 **4색(빨·노·초·파) 빛이 오버레이 둘레를 돈 뒤 파랑 단색으로 바뀌고 사라짐**(Google 앱 16.30, 삼성에도 적용). [확인: Sammy Fans 2025-08-10] → 2025-11 **화면 전체 둘레를 도는 전체화면 빛**을 일부 사용자에서 시험(파랑 주조 + 초·빨·노, 일부 물결이 화면 안쪽으로 번짐, 입력창 둘레 빛은 제거). [확인: 9to5Google 2025-11-24]
- **Gemini Live 화면 공유(Astra)**: 상태바의 통화형 알림 + 하단 파란 파형 + 둘레 빛. [확인: 9to5Google 2025-03-24]
- **에이전트가 기기를 조작할 때**: I/O 2025 Project Astra 데모에서 **Gemini 가 앱을 조작하는 동안 화면 둘레에 지속되는 파란 빛 + 챗헤드(떠 있는 원형 버튼)**. [확인: 9to5Google 2025-11-24 본문 언급] ← 요구 ②에 가장 가까운 선례.
- 두께·주기의 공식 수치: **미확인**.

### 4-2. Samsung Galaxy AI
- One UI 8: 설정 > 유용한 기능 > 측면 버튼 > 길게 누르기 에서 Gemini 지정. [검색요약: Samsung 지원 페이지]
- 갤럭시의 AI 호출 빛은 **Gemini 오버레이의 빛을 그대로 씀**(4색 → 파랑). [확인: Sammy Fans] Material 3 Expressive 의 "Glow Overlay"(다색 빛 + 하단 옅은 점 무늬)라는 이름은 검색 요약에만 나옴 — 원문 미열람.
- 삼성 고유의 "에이전트 조작 중 테두리 빛" 사양: **미확인**.

### 4-3. Apple Intelligence Siri
- Siri 호출 시 **화면 네 변을 감싸는 다색 빛 테두리**(iOS 18.1~). 색은 보라·분홍·파랑·주황 계열. [검색요약: pocket-lint 등]
- 회전 주기 "약 1.8초" 수치는 공식 출처가 아닌 판매 사이트 요약문에만 있음 — **미확인으로 취급**.
- 비공식 재현(GitHub jacobamobin/AppleIntelligenceGlowEffect) 구현값 [확인]: 색 7개 `#BC82F3 #F5B9EA #8D9FFF #AA6EEE #FF6778 #FFBA71 #C686FF`, **흐림 없는 선 5pt + 흐린 선 7pt(blur 4) 겹침**, 그라디언트 위치를 0.5~0.6초 easeInOut 으로 무작위 갱신. 공식값 아님.

### 4-4. 비교표

| | Gemini(안드로이드) | Galaxy AI | Siri(Apple) | 폴드 에이전트 요구 ② |
|---|---|---|---|---|
| 위치 | 오버레이 둘레 → 화면 전체 둘레(시험) | Gemini 것 사용 | 화면 네 변 | 화면 네 변 |
| 색 | 파랑/보라 → 4색 후 파랑 | 동일 | 보라·분홍·파랑·주황 | **은은한 붉은색 단색** |
| 움직임 | 둘레를 흐르며 돌다 사라짐 | 동일 | 다색이 흐르며 회전 | **밝기만 숨쉬듯 반복(흐르지 않음)** |
| 조작 중 표시 | Astra 데모: 지속 파란 빛 + 챗헤드 | 미확인 | 해당 없음(Siri 호출 표시) | 조작 중 지속 |
| 두께·주기 | 미확인 | 미확인 | 비공식 재현 5pt+7pt(blur) | 결정 필요 |

**함의**: 붉은 단색 + 숨쉬기(밝기 진폭)는 경쟁사들의 "다색이 흐르는 빛"과 확실히 구별된다. 참고 수치로 쓸 만한 것은 비공식 재현의 "선명한 얇은 선 + 흐린 넓은 선 2겹" 구조 정도. 숨쉬기 주기(예: 3~4초 왕복)·최소/최대 불투명도는 시안으로 비교해 사용자가 결정할 사항.

---

## 출처
- Meta 뉴스룸 "Introducing Muse" (2026-09-08): https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/ [확인]
- "How We Designed Muse": https://introducing.muse.ai/ [확인] · UI 이미지 `/_next/static/immutable/media/{status,goals,checkout}.*.png` [확인]
- Meta 블로그 Connect 2026 (2026-09-24): https://www.meta.com/blog/meta-connect-2026-everything-we-announced/ [확인]
- TechCrunch "Everything new coming to Meta's AI agent Muse" (2026-09-23): https://techcrunch.com/2026/09/23/everything-new-coming-to-metas-ai-agent-muse/ [확인]
- CNN 체험기 (2026-09-23): https://www.cnn.com/2026/09/23/tech/meta-muse-ai-agent [확인]
- NBC News (2026-09-25): https://www.nbcnews.com/tech/tech-news/meta-muse-ai-agent-response-animated-avatar-cute-rcna599736 [확인]
- Lenny's Newsletter 리뷰 (2026-09-16): https://www.lennysnewsletter.com/p/muse-review-the-personal-ai-agent [확인: 목차만, 영상 미시청]
- 가격·다운로드 수: https://tech-insider.org/meta-muse-personal-ai-agent-launch-2026/ , https://www.foxbusiness.com/technology/metas-muse-becomes-app-stores-hottest-download [검색요약]
- 9to5Google Gemini 오버레이 개편 (2025-01-24): https://9to5google.com/2025/01/24/gemini-overlay-redesign-rolling-out/ [확인]
- 9to5Google 전체화면 빛 (2025-11-24): https://9to5google.com/2025/11/24/gemini-overlay-fullscreen/ [확인]
- 9to5Google Astra 화면공유 빛 (2025-03-24): https://9to5google.com/2025/03/24/gemini-astra-screen-glow/ [확인]
- Sammy Fans Gemini 4색 애니메이션 삼성 적용 (2025-08-10): https://www.sammyfans.com/2025/08/10/gemini-4-color-animation-rolling-out-to-samsung-phones/ [확인]
- Samsung One UI 8 측면 버튼: https://www.samsung.com/us/support/answer/ANS10007435/ [검색요약]
- Siri 빛 비공식 재현: https://github.com/jacobamobin/AppleIntelligenceGlowEffect [확인] · pocket-lint: https://www.pocket-lint.com/how-to-get-new-siri-look-glowing-border/ [검색요약]
