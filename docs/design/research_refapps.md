# 참고 앱 분석 — cmux 관제실 · Claude 기록 → 폴드 에이전트 디자인 언어

> 작성 2026-09-26 · 폴드 에이전트 UI/UX 새로 만들기((인계 문서, 비공개 — docs/JOURNEY.md 참고))의 벤치마킹 조사 1건
> 대상: `<참고 앱 저장소>/android/`
> (`app` = cmux 관제실 `kr.joonlab.cmuxremote` · `history` = Claude 기록 `kr.joonlab.cchistory` · `core` = 공용)
> 폰(adb)에는 아무 명령도 보내지 않았다. 모든 수치는 아래 파일을 직접 읽어 얻었고, 못 잰 것은 「미확인」으로 적었다.

아래에서 `R` = `<참고 앱 저장소>`, `A` = `R/android`.

---

## 0. 한눈에

- 두 앱은 **같은 뼈대(core)에 색만 갈아 끼우는 구조**다. `CorePalette` 인터페이스 10토큰 + 앱별 `object`(관제실 `C` = Emerald Noir, 기록 `H` = 진홍 팔레트). 폴드 에이전트도 같은 방식으로 제 팔레트를 하나 더 만들면 된다.
- 디자인 언어의 핵심은 **평평한 면 + 1dp 테두리 + 둥근 모서리(10~14dp) + 상태는 면에 옅게 물들이기(알파 .09~.17)**. 그림자와 Material 기본 컴포넌트를 거의 쓰지 않는다. Material3는 `Text`·`Icon`·`DropdownMenu`·`PullToRefreshBox` 정도만 쓰고, 나머지는 `Box/Row + background + border`로 직접 그린다.
- 폴드 대응은 **폭 600dp 기준으로 한 칸/여러 칸**을 나누고, 칸 사이에 끌 수 있는 레일(`SplitPane`)을 둔다. 기록 앱은 반접힘(테이블톱·책)까지 다룬다.
- 두 참고 앱과 폴드 에이전트의 **AGP(9.4.1)·Gradle(9.7.1)·compileSdk(36)·minSdk(30)가 똑같다.** 참고 앱에서 검증된 Compose 조합을 그대로 옮길 수 있다.
- 추천: **앱 화면(대시보드·작업 기록·팝업 입력)은 Compose로 옮기고, 접근성 오버레이(상단 패널·테두리 빛)는 기본 View로 둔다**(§10).

---

## 1. 읽은 자료

| 종류 | 경로 |
|---|---|
| 팔레트 | `A/app/src/main/java/kr/joonlab/cmuxremote/Ui.kt`(`object C`) · `A/history/src/main/java/kr/joonlab/cchistory/Palette.kt`(`object H`) |
| 테마 주입 | `A/core/src/main/java/kr/joonlab/core/Theme.kt`(`CorePalette`·`ThemeMode`·`Pretendard`·`CoreTheme`) |
| 폴드 | `A/core/.../Split.kt`(`SplitPane`·`WIDE`) · `A/history/.../Posture.kt`(힌지·테이블톱·책) · `A/history/.../MainActivity.kt`(자세 분기) |
| 목록·대화 | `A/history/.../ListUi.kt` · `A/history/.../ConvUi.kt` · `A/app/.../MainActivity.kt`(`ChatRow`·`ToolGroup`·`QuestionCard`) · `A/app/.../Answer.kt`(`Sheet`·`Composer`·`Field`) |
| 글자·아이콘 | `A/core/.../TextSize.kt` · `A/core/.../Icons.kt` · `A/core/.../IconsGen.kt` · `A/history/.../HeroGen.kt` · `A/core/.../Markdown.kt` |
| 빌드 | `A/settings.gradle.kts` · `A/build.gradle.kts` · `A/{app,history,core}/build.gradle.kts` · `A/gradle.properties` · `A/gradle/wrapper/gradle-wrapper.properties` |
| 문서 | (인계 문서) · `R/README.md` · `R/docs/history-app/{DESIGN,VIEWER-INVENTORY,HANDOFF-M2~M7,PROMPT,PASTE-TO-CURRENT}.md` |
| 캡처 | `R/docs/history-app/shots/m3/*.jpg` · `shots/m4/*.jpg` · `shots/m5/*.jpg` (6장 직접 열어 봄, §8) |

`gradle/libs.versions.toml`은 **없다.** 버전은 `build.gradle.kts`에 직접 박혀 있다.

---

## 2. 색 팔레트 (실제 hex)

### 2-1. 공용 토큰 — `CorePalette` (core/Theme.kt)
`bg · panel · panel2 · raised · border · text · dim · accent · accentTint · onAccent` 10개. 값은 getter라서 `ThemeMode.dark`를 읽는 자리만 테마 전환 때 다시 그려진다.

### 2-2. 관제실 — Emerald Noir (`Ui.kt` `object C`)

| 토큰 | Light | Dark | 역할 |
|---|---|---|---|
| bg | `#FBFCFB` | `#0A0F0D` | 바탕 |
| panel | `#F2F5F3` | `#101714` | 카드·입력 바닥 |
| panel2 | `#E7EBE8` | `#17201C` | 칩·보조 버튼 |
| sunken | `#EEF2EF` | `#070B09` | 코드·명령줄 박스 |
| raised | `#FFFFFF` | `#1B2521` | 시트·대화상자 |
| border | `#C3CCC7` | `#243128` | 1dp 테두리 |
| text | `#16201B` | `#E8EEEA` | 본문 |
| dim | `#555C5B` | `#98A49E` | 보조 글자 |
| accent | `#0F5132` | `#3CC492` | 에메랄드(브랜드) |
| accentTint | accent α.12 | 〃 | 선택·내 말풍선 |
| onAccent | `#FFFFFF` | `#03150E` | |
| gold | `#7D6740` | `#C4A575` | 브랜드 보조 |
| green(작업 중) | `#1A7F3C` | `#5ED18A` | 의미층 |
| lemon(뒤에서) | `#7A6A12` | `#DFC95F` | 의미층 |
| amber(막힘) | `#8A6100` | `#D9AE45` | 의미층(권한·질문) |
| amberText | `#6B4C00` | `#E6C97E` | |
| claude(입력 대기) | `#A8501F` | `#D9855F` | 의미층 |
| violet(워크플로) | `#5B45C9` | `#A394F0` | 의미층 |
| red | `#C4322C` | `#EF6157` | 오류·판정 불가 |
| edgeIdle / groupFallback | `#BFC8C3`/`#31403A` · `#8A948F`/`#3B4A43` | | |

**규칙(Ui.kt 주석 원문 요지)**: [브랜드층] accent·dim·border·gold는 도구가 자기를 드러내는 곳(링크·선택·버튼), [의미층] green·lemon·amber·claude·red는 **상태 — 카드 배경 물듦과 작은 아이콘으로만**. 카드 왼쪽 3px 띠 = 사람이 칠한 워크스페이스 색. 물듦 농도는 `statusTint()`: running .13 · permission/question .16 · workflow .15 · background .17 · waiting .09 · unknown .12.

### 2-3. Claude 기록 — 진홍 팔레트 (`Palette.kt` `object H`, 웹 뷰어 토큰 원문)

| 토큰 | Light | Dark |
|---|---|---|
| bg (--bg-paper) | `#FFFFFF` | `#141414` |
| panel (--bg-primary) | `#F6F6F7` | `#1A1A1B` |
| panel2 (--bg-secondary) | `#EEEEF0` | `#232325` |
| tertiary | `#E5E5E7` | `#2C2C2E` |
| raised | `#FFFFFF` | `#232325` |
| border | `#E3E3E5` | `#38383A` |
| borderHover | `#C9CACC` | `#515153` |
| text | `#1A1A1A` | `#ECECEC` |
| dim | `#6B6B6B` | `#A6A6A8` |
| muted | `#8A8D8F` | `#8A8D8F` |
| **accent (진홍)** | **`#A50034`** | **`#E14C74`** |
| accentTint | `#FBEAF0` | `#3A0E1F` |
| gold (Claude 아바타) | `#85714D` | `#C2A878` |
| goldTint / goldText / goldLine | `#F4EFE5`/`#2A2418` · `#6E5C3B`/`#D9C49A` · `#D9CDB6`/`#4A3F2C` | |
| danger | `#C0392B` | `#E06C64` |
| success | `#4E7A3F` | `#7FA766` |
| tagBg / tagFg | `#E0F2FE`/`#0C2A3D` · `#0369A1`/`#7CC4F0` | |
| liveBg / liveFg (살아 있음 배지) | `#E6F4EA`/`#12301C` · `#1E6B3A`/`#9BE3B3` | |
| markBg (검색 형광) | `#E8B339` α≈.35 (`0x5AE8B339`) | 〃 |
| 대화 찾기 현재 매치 테두리 | `#F97316` 2dp | 〃 |

런처 배경: 관제실 `#0A0F0D`, 기록 `#A50034`(`res/values/colors.xml`).

> 폴드 에이전트에 주는 시사점: 요구 ②의 「은은한 붉은 빛」과 진홍 계열(`#A50034`/`#E14C74`)은 결이 맞는다. 다만 기록 앱과 색이 겹치면 두 앱이 구분되지 않는다(DESIGN D6는 「색으로 두 앱을 구분한다」를 확정 원칙으로 뒀다). **포인트 색은 사용자에게 물어서 정할 결정**이다(§9 원칙 1).

---

## 3. 다크/라이트

- `ThemeMode.mode` 가 **system → light → dark** 세 상태를 돈다(`cycle()`, 웹 대시보드 theme.js와 같음). SharedPreferences `"ui"`의 `"theme"` 키에 저장한다.
- 기본값: 관제실은 `system`, 기록 앱은 **`light`**(웹 뷰어 기본과 맞춤 — 사용자 결정 2026-09-25, `history/MainActivity.kt` 주석).
- `CoreTheme`이 테마에 맞춰 상태 바·내비 바 글자색(`isAppearanceLightStatusBars`)도 바꾼다. `enableEdgeToEdge()` + `safeDrawingPadding()`.
- 매니페스트 테마는 `@android:style/Theme.Material.Light.NoActionBar`(플랫폼 테마). AppCompat 의존성 없음.
- 머리의 해/달 아이콘 버튼(`IconBtn("sun"|"moon")`)으로 전환.

---

## 4. 타이포

- 글꼴 **Pretendard** 4굵기(Regular·Medium·SemiBold·Bold, `core/src/main/res/font/*.otf`, **합계 6.3MB**). `CoreTheme`이 `LocalTextStyle`로 기본 글꼴을 깐다. 코드·경로는 `FontFamily.Monospace`.
- 실제로 쓰는 크기(sp) — 코드에서 직접 뽑음:

| 용도 | 크기 / 굵기 |
|---|---|
| 앱 이름(Brand) | 19 Bold(큰 화면) / 16 |
| 섹션 머리(상태 묶음·맥 이름·시트 제목) | 14~15 Bold, 시트 제목 17 Bold |
| 카드 제목 | 관제실 15 SemiBold(2줄) · 기록 14(이름 있으면 SemiBold, 2줄, lineHeight 19) |
| 대화 머리 제목 | 15.5 Bold, lineHeight 21 |
| 말풍선 본문 | 기록 14 / lineHeight 22 · 관제실 14.5 / 22~23 |
| 보조(위치 줄·메타·스니펫) | 12~12.5, lineHeight 17~18 |
| 칩·배지·시간 | 11~12.5 (칩 12.5, 태그 11 SemiBold, 시간 11~12) |
| 통계 숫자 | 17 Bold accent + 라벨 10 |
| 입력칸 | **16 이상**(16 미만이면 폰이 확대한다 — ListUi.kt 주석) |
| 아래 탭 라벨 | 11 SemiBold + 아이콘 22dp |

- 굵기는 SemiBold(600)가 주력, 강조 Bold, 본문 Normal. 웹 뷰어 통계도 600 > 500 > 700 순(VIEWER-INVENTORY §3-2).
- **읽는 영역만 글자 배율**: `TextSize.kt` — 머리의 「가」 버튼(85~160%, 10%씩)과 두 손가락 핀치. `fontScale`로 걸어 sp 글자만 커지고 dp 여백·아이콘은 그대로.

---

## 5. 모양 — 모서리·여백·크기

| 부품 | 모서리 | 여백·크기 | 파일 |
|---|---|---|---|
| 상태 카드(관제실 TabCard) | 10dp, 1dp 테두리, 왼쪽 3dp 색 띠, 상태 물듦 | 바깥 14×4, 안 15/12/12/12, 최소 높이 48, 요소 간격 12 | Ui.kt `Modifier.card` |
| 목록 줄(기록 SessionRow) | 없음(평평한 줄) — 아래 1dp 구분선, 선택 시 accentTint + 왼쪽 3dp accent | 16/14/11/11, 줄 간격 5 | ListUi.kt |
| 테이블톱 카드 | 12dp, 선택 시 accent 테두리 | 폭 212, 12×10 | Posture.kt |
| 칩(필터) | **완전 알약**(높이/2), 1dp 테두리, on이면 accentTint+accent | 높이 34(32·42 변형), 가로 12 | ListUi.kt `Chip` |
| 태그 칩 | 5dp | 7×2 | `TagChip` |
| 아이콘 버튼 | 10dp, panel2 바탕 | 40dp(36·44 변형), 아이콘 18 | `IconBtn` |
| 검색칸 | 12dp, panel2, 테두리 없음 | 높이 40/46 | `SearchField` |
| 입력칸(관제실) | 14dp, raised + 1dp 테두리 | 최소 46, 14×12 | Answer.kt `Field` |
| 보내기 버튼 | 원(24dp 반경) | 48dp, 비활성 panel2 | `Composer` |
| **내 말풍선** | 기록 14/14/**4**/14(오른쪽 아래 꼬리) · 관제실 18/18/**6**/18 | 13×10 / 14×10, 최대 폭 560 / 520 | ConvUi · MainActivity |
| **상대 말풍선(Claude)** | 14/14/14/**4**(왼쪽 아래 꼬리), bg + 1dp 테두리 | 최대 폭 720, 아바타 26dp 원(gold, 「C」) | ConvUi.kt |
| 도구 묶음 | 8~10dp, 실행 중이면 accent 테두리 | 접힘 1줄 → 펼침 | `ToolGroup` |
| 질문 카드 | 12~14dp, goldTint/amberTint 바탕 + 선 | | `QuestionCard` |
| 시스템 칩 | 10dp **점선** 테두리 | | `SysChip` |
| 아래 시트(관제실 답하기) | 위 22dp, 손잡이 40×4 | 끌어내리면 한 줄로 접힘 | Answer.kt `Sheet` |
| 위에서 덮는 시트(기록 폴더·태그) | 아래 18dp, 뒤 스크림 `#66000000` | 최대 높이 620 | ListUi.kt `Sheet` |
| 대화상자 | 18dp, raised | 최대 높이 700 | Tree.kt `DialogBox` |
| 떠 있는 알약(따라보기·토스트) | 18~20dp, **text 색 바탕 + bg 색 글자**(반전) | 높이 36~40 | ConvUi `FollowPill` · Posture `Pill` |
| 주 버튼 | 12~14dp | 최소 50, 15 Bold | Answer.kt `PrimaryButton` |

반복되는 수치: **반경 5(태그)·8~10(작은 면)·12~14(카드·말풍선·입력)·18~22(시트)**, 1dp 테두리, 줄 간 4~12dp, 터치 타깃 40~48dp(DESIGN §4 「44dp 이상」).

---

## 6. 폴드 자세 대응

### 6-1. 기록 앱(`history/MainActivity.kt` + `Posture.kt`) — 다섯 배치

| 자세 | 판정 | 배치 |
|---|---|---|
| **cover**(접힘) | 폭 < 600dp(`WIDE`) | 한 화면씩: 목록 → 대화. **아래 탭 막대 4칸**(목록·폴더·태그·맨 위 / 대화 중엔 목록·대화 찾기·북마크·맨 아래) |
| **port**(펼침 세로) | 폭 < 840dp 또는 세로 | **2칸**: 목록 \| 대화 (`SplitPane`) |
| **land**(펼침 가로) | 그 외 | **3칸**: 사이드바(브랜드·통계·검색·필터·폴더 트리) \| 목록 \| 대화. 나란히 보기 중엔 사이드바를 빼고 목록 \| 대화 \| 대화(933dp에 안 들어가서 — 실측) |
| **table**(반접힘, 가로 힌지) | 힌지 각도 30~150°에 0.5초 머묾 + 힌지 가로 | 위: 대화 읽기 / 힌지 띠(24dp, 글자 없음) / 아래: 조작판(찾기·따라보기·북마크·필터 칩 + **가로 카드 줄**). 아래 칸 접기 가능 |
| **book**(반접힘, 세로 힌지) | 〃 + 힌지 세로 | 왼쪽 목록 \| 힌지 띠 \| 오른쪽 대화 — 힌지를 칸 경계로 |

- ★실측 함정(Posture.kt 주석): **폴드8은 `FoldingFeature`를 반쯤 접어도 FLAT으로만 준다.** 힌지 위치·방향은 FoldingFeature에서, 접힘 정도는 공개 센서 `Sensor.TYPE_HINGE_ANGLE`로 따로 읽는다. 접고 펼 때 90°를 스치므로 0.5초 머물러야 반접힘으로 인정.
- 화면 실측(DESIGN §4): 커버 1248×1972px = **475×751dp**, 안쪽 2448×1848px = **933×704dp**(420dpi).
- 자세를 바꿔도 선택·스크롤·검색어가 남도록 매니페스트 `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|uiMode"` + 상태는 뿌리 `remember`의 `AppState`에.

### 6-2. 칸 레일(`core/Split.kt` `SplitPane`)
- 칸 | 레일 | 나머지. 레일 **탭 = 접기/펼치기, 좌우 끌기 = 폭 조절**, 기준 밑으로 끌면 접힘. 폭·접힘은 SharedPreferences에 `key` 별로 저장(자세별로 key를 달리 줌: `list-port`, `list-land` …).
- 관제실은 레일 30dp·목록 360dp, 기록 앱은 레일 14dp·120dp 밑이면 접힘·대화 최소 320dp.

### 6-3. 관제실(`app/MainActivity.kt`)
- 폭 ≥ 600dp면 목록 | 대화 2칸(`rememberPaneState("list", 360f)`), 「전체 화면」 체크로 대화 칸을 숨기고 목록만(상황판) 가능. 커버는 한 화면씩.

---

## 7. 목록·대화 화면 구조와 관리 UX

### 7-1. 목록(기록 `ListUi.kt`)
- 머리(`ListHead`): **로고 + 앱 이름 + 테마 버튼** → **출처 줄**(「홈맥에서 읽음 · 인덱스 1분 전 · cmux 열림 53」 — 눌러서 새로 고침, 오류면 빨강) → 검색칸 → 필터 칩 가로 스크롤.
- 필터 칩: 폴더 · 날짜(전체→오늘→7일→30일 순환) · 태그(개수 표시) · 북마크 · cmux 열림 · 검색 방식(하이브리드/키워드/의미) · 초기화. 켜진 칩은 accentTint+accent.
- 목록: `PullToRefreshBox` + `LazyColumn`, **날짜 묶음 sticky 머리**(「오늘 (56)」, panel2 바탕).
- 세션 줄: 윗줄(살아 있음 배지 · 시각 · 북마크 · 메시지 수 알약) → 제목 2줄 → 검색 스니펫(왼쪽 2dp 형광 선, 일치 부분 형광) → 태그 최대 5 → 폴더(accent).
- 폴더는 시트/사이드바의 트리(펼침 화살표 · 색 네모 · 이모지 · 이름 · 개수 알약).
- 빈 상태: 아이콘 36~40dp(muted) + 안내 한 줄 + 「빌드 MM-dd HH:mm」.

### 7-2. 목록(관제실) — 상태 묶음
- 맨 위 **「나를 기다림 N개」 요약 줄**(amber) → 상태별 묶음 머리(「◌ 작업 중 2개」) → 카드. 카드 = 상태 아이콘(작업 중이면 회전) · 제목 15/600 · 위치 줄(창 › 그룹 › 워크스페이스 · `tab:N` 모노 태그) · 따옴표 친 마지막 프롬프트 · 오른쪽 경과 시간 · 「방금 끝남」 배지. 유휴는 흐리게(alpha .72/.9).

### 7-3. 대화(기록 `ConvUi.kt`)
- 머리: 뒤로 · 제목(2~3줄) · 메타 한 줄(메시지 수 · 프로젝트 · 크기 · 어느 맥) · 「가」 → 태그 → 설명(왼쪽 3dp accent 선 박스, 눌러서 펼침) · 폴더 알약 → 살아 있음 배지 · 이어가기 · 도구/시스템 보기 토글 · 찾기 · 북마크.
- 본문: `LazyColumn` 간격 12dp, **뒤에서부터 페이지**(맨 위 「↑ 이전 대화 불러오기」), 열자마자 맨 아래.
- `rowsOf()`: **연속 도구 호출은 한 줄로 접고**(「도구 3개 · Bash ×2 · Read」), Claude가 이어 말하는 동안 아바타·이름은 한 번만.
- 말풍선: 사람 = 오른쪽 accent 바탕, Claude = 왼쪽 gold 원 아바타 + 테두리 말풍선 + 마크다운, 질문 = gold 카드(선택지 번호 + 「→ 답」), 시스템 = 점선 칩, 구분선 = 가운데 글자.
- 따라보기: 살아 있는 세션은 4초마다 꼬리를 받고, 위로 올리면 멈추고 「새 메시지 N ↓」 알약.
- 대화 안 찾기(`FindBar`): n/N · ↑↓ · 닫기, 현재 매치 주황 2dp 테두리.
- 관제실 대화는 더 단순: 사람 = 오른쪽 accentTint(연한 에메랄드) 말풍선, Claude = **말풍선 없이 맨 글자**, 아래 `Composer`(키 줄 Esc·⏎·⇧Tab·↑·↓ + 스니펫 줄 + 입력칸 + 둥근 ↑ 보내기).

### 7-4. 관리 UX — 검색·필터·삭제·보관
- **있음**: 검색(본문 검색은 엔터로 서버 호출, 제목·태그는 앱이 인덱스로 거름), 필터 칩, 폴더 트리, 태그 시트, 북마크(유일한 쓰기, 서버 파일), 당겨서 새로 고침, 길게 눌러 나란히 보기.
- **없음 — 삭제·보관은 구현되지 않았다.** DESIGN D4에서 「⑦ 삭제 — 제외」로 확정했고, 이유는 「되돌리기 어렵다. 넣는다면 휴지통 이동」, 큐레이션 쓰기도 D2-c에서 제외(읽기 전용). 웹 뷰어의 Delete가 실제로 파일을 지운다는 점을 이식 주의사항으로 적어 뒀다(DESIGN §2).
- → **폴드 에이전트의 작업 기록 삭제·보관은 참고할 선례가 없다. 새로 설계해야 한다.** 참고 앱에서 가져올 수 있는 원칙은 「삭제는 되돌릴 수 있게(휴지통 방식)」와 「확인 게이트는 약하게 만들지 않는다」 정도다(§9 원칙 9).

---

## 8. 캡처로 본 인상 (직접 열어 본 6장)

| 캡처 | 인상 |
|---|---|
| `shots/m3/cover-list-light.jpg` | 흰 바탕에 진홍 포인트가 로고·선택 줄·아래 탭에만 있어 차분하다. 알약 칩 줄이 가로로 흐르고, 날짜 머리가 회색 띠로 끊어 준다. 선택 줄은 옅은 분홍 바탕 + 왼쪽 빨간 띠. 초록 「● 노트북 · 질문 대기」 배지가 눈에 가장 먼저 들어온다 |
| `shots/m3/cover-detail-dark.jpg` | 다크는 거의 검정(#141414) 위에 테두리 말풍선. gold 「C」 아바타가 따뜻한 포인트. 도구 줄과 점선 시스템 칩이 대화를 방해하지 않게 한 줄로 눌려 있다. 「맨 아래 ↓」 흰 알약이 떠 있다 |
| `shots/m3/land-3pane-dark-logo.jpg` | 가로 3칸이 웹 데스크톱과 거의 같다. 사이드바 통계(2.8K 세션 · 624.7K 메시지 · 53)가 빨간 숫자로 대시보드 느낌을 준다. 칸 경계의 점 세 개 레일이 끌 수 있음을 알려 준다 |
| `shots/m4/real-tabletop.jpg` | 위 대화 / 아래 조작판이 힌지 띠로 정확히 갈린다. 아래 카드는 큰 둥근 사각형 3장이 옆으로 흐르고 선택 카드는 빨간 테두리+짙은 빨강 바탕 |
| `shots/m5/3-control-chat-after-badge.jpg` | 관제실(라이트): 에메랄드 톤. 「나를 기다림 2개」 요약 줄 아래 amber 물든 카드, violet 물든 워크플로 카드 — **상태 = 면 색** 규칙이 한눈에 보인다. 오른쪽 대화는 키 줄·스니펫 칩·입력칸이 아래에 모여 있다 |
| `shots/m5/2-resume-sheet-running.jpg` | 가운데 대화상자(18dp, 흰 바탕)에 맥별 선택 카드(연회색 면) + 짙은 에메랄드 주 버튼. 경고는 빨간 한 줄 글자로. 위험한 선택을 **맥 이름을 명시한 카드**로 고르게 한다 |

공통 인상: **장식이 적고 정보 밀도가 높다.** 그림자·그라데이션이 없다. 색은 포인트 하나 + 상태 색 몇 개로 절제하고, 상태·출처(어느 맥·몇 분 전)를 늘 글로 드러낸다.

---

## 9. 폴드 에이전트에 옮길 디자인 원칙 (11개)

1. **팔레트는 `CorePalette` 10토큰 + 앱 전용 `object` 하나.** 라이트/다크 쌍을 getter로 두고 system→light→dark 3상태 전환. 포인트 색은 한 가지만 — 붉은 계열이 유력하지만 기록 앱(진홍)과 겹치지 않게 **사용자에게 물어서 정한다.**
2. **브랜드층과 의미층을 나눈다.** 포인트 색은 선택·링크·주 버튼에만, 상태(실행 중·확인 대기·성공·실패·취소)는 따로 정한 의미 색으로 **카드 배경을 옅게(알파 .09~.17) 물들이고 작은 아이콘**으로 표시한다. 글자를 빨갛게 칠하는 식으로 쓰지 않는다.
3. **평평한 면 + 1dp 테두리 + 반경 체계(5 / 10 / 14 / 20).** 그림자·그라데이션을 쓰지 않는다. 예외 하나는 **테두리 빛(요구 ②)**. 앱 전체가 평평해서 빛이 켜지면 바로 눈에 띈다.
4. **Pretendard + 크기 5단계**(제목 15~17 Bold · 카드 제목 14~15 SemiBold · 본문 14 / 줄 22 · 보조 12~12.5 · 칩 11~12). 입력칸은 16sp 이상. 읽는 영역만 「가」·핀치로 배율 조절.
5. **작업 기록 = 목록 줄 + 대화 화면, 기록 앱 방식.** 줄에는 상태 배지·일시·제목 2줄·설명 1~2줄(스니펫 스타일)을, 대화에서는 사람 말풍선 오른쪽 / 에이전트 왼쪽(아바타 한 번만). 연속 단계(탭·스와이프·읽기)는 「단계 5개 · 탭 ×3 · 입력」 **한 줄로 접는다**(`rowsOf` 방식).
6. **날짜 묶음 sticky 머리 + 알약 필터 칩**(전체/오늘/7일, 상태별, 북마크)과 검색칸. 빈 상태는 아이콘 + 안내 한 줄 + 빌드 시각.
7. **폭 600dp로 한 칸/두 칸.** 커버에서는 목록 → 대화 한 화면씩 + **아래 탭 4칸**, 펼치면 목록 \| 대화 `SplitPane`(끌 수 있는 레일, 폭은 자세별 저장), 필요하면 가로 3칸(사이드바에 통계). 반접힘은 2단계로 미룬다.
8. **입력은 아래에 모은다.** 관제실 `Composer`처럼 둥근 입력칸 + 원형 보내기(비활성 회색) + 마이크. 팝업 입력(요구 ①)도 같은 부품을 쓰고, 화면 아래에서 올라오는 시트(위 20~22dp, 손잡이 40×4, 끌어내려 접기).
9. **위험한 동작은 명시적인 카드로 고른다.** 이어가기 시트처럼 대상을 이름으로 보여 주고 주 버튼 하나를 둔다. 발송·결제·삭제 확인 게이트는 UI를 바꿔도 **약하게 만들지 않는다.** 기록 삭제는 되돌릴 수 있게 한다(휴지통·보관 먼저, 영구 삭제는 한 번 더 확인).
10. **출처·상태를 늘 글로 드러낸다.** 「실행 중 · 2단계/5 · 12초」, 「취소됨 — 사용자가 멈춤」처럼 쓰고, 모르면 「unknown」으로 적는다. 떠 있는 알약(반전 색: text 바탕 + bg 글자)은 「맨 아래」·토스트에 쓴다.
11. **모션은 최소한으로, 의미 있는 곳에만.** 참고 앱의 애니메이션은 실행 중 아이콘 회전(1.1초 선형)과 스크롤 이동뿐이다. 폴드 에이전트에서는 **테두리 숨쉬기**(느린 사인 알파, 예 2.5~4초 주기 — 값은 시안으로 정할 것) 하나를 주인공으로 두고, 나머지는 짧은 페이드 정도로 둔다.

---

## 10. 「기본 View 유지 vs Compose 전환」

### 10-1. 지금 폴드 에이전트 빌드 (직접 확인)
- `foldagent/build.gradle.kts`: `com.android.application` **9.4.1**만(apply false). Kotlin은 AGP 내장.
- `foldagent/settings.gradle.kts`: `:app` 하나. `gradle/wrapper/gradle-wrapper.properties`: **Gradle 9.7.1**. `libs.versions.toml` 없음.
- `app/build.gradle.kts`: compileSdk 36 · minSdk 30 · targetSdk 36 · `buildConfig = true` · release `isMinifyEnabled = false` · **`dependencies { }` — 의존성 0**(org.json·HttpURLConnection·SpeechRecognizer 전부 플랫폼).
- `gradle.properties`: `android.useAndroidX=true`, JDK 21(openjdk@21) 고정 — 참고 앱과 같다.
- UI: `MainActivity.kt` 620줄(코드로 만든 View), `Overlay.kt` 182줄(접근성 오버레이, `GradientDrawable` 18dp 반경 `#E6202124`), 말풍선 색 `#2F5FD0`(내 것)/`#2E2F33`.
- APK(debug, 2026-09-26 22:37 빌드): **2,948,566 B ≈ 2.9MB**, dex 합계 약 2.85MB(비압축).

### 10-2. 참고 앱 빌드 (직접 확인)
- 루트 `build.gradle.kts`: `com.android.application` 9.4.1 · `com.android.library` 9.4.1 · **`org.jetbrains.kotlin.plugin.compose` 2.4.20** (모두 apply false). 주석: 「AGP 9.0부터 Kotlin 지원이 AGP에 내장 — `kotlin.android` 플러그인은 넣으면 오히려 에러」.
- 앱 모듈: `plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }`, `buildFeatures { compose = true; buildConfig = true }`.
- core: `api(platform("androidx.compose:compose-bom:2026.06.01"))` + `ui` · `material3` · `foundation` + `androidx.activity:activity-compose:1.12.4`. 주석: 「2026-09-20 foldlab 검증 조합 — **BOM 2026.08+는 compileSdk 37 요구**」.
- 기록 앱만: `androidx.window:window:1.5.0`(힌지).
- Gradle 9.7.1 · compileSdk 36 · minSdk 30 — **폴드 에이전트와 같다.**
- AGP 9.4.1 내장 Kotlin의 정확한 버전: **미확인**(compose 컴파일러 플러그인 2.4.20과 맞는 조합으로 실제 빌드되고 있다는 것만 확인).
- APK(debug): 관제실 35,165,986 B · 기록 35,604,138 B ≈ **35MB**. 기록 APK 안: dex 합계 약 29.3MB(비압축), Pretendard 4개 6.3MB. release/R8 APK는 없어서 **release 크기는 미확인**.

### 10-3. Compose로 옮길 때 추가할 것
1. 루트 `build.gradle.kts`: `id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false`
2. `app/build.gradle.kts`: `plugins`에 같은 id, `buildFeatures { compose = true }`
3. 의존성:
   ```kotlin
   implementation(platform("androidx.compose:compose-bom:2026.06.01"))   // 2026.08+ 는 compileSdk 37 필요
   implementation("androidx.compose.ui:ui")
   implementation("androidx.compose.foundation:foundation")
   implementation("androidx.compose.material3:material3")               // Text·Icon·DropdownMenu·PullToRefreshBox 용
   implementation("androidx.activity:activity-compose:1.12.4")
   // 펼침·반접힘을 다룰 때만: implementation("androidx.window:window:1.5.0")
   ```
4. 글꼴: `res/font/pretendard_*.otf` 복사(4개 6.3MB, 또는 Regular+SemiBold 2개 약 3.2MB만).
5. 매니페스트: 액티비티에 `configChanges`(§6-1) · `windowSoftInputMode="adjustResize"` · 테마 `@android:style/Theme.Material.Light.NoActionBar` + `enableEdgeToEdge()`.
6. **접근성 서비스 오버레이에 Compose를 쓰려면** `ComposeView`에 `ViewTreeLifecycleOwner`·`SavedStateRegistryOwner`를 직접 달아야 한다(액티비티 밖이라). 참고 앱에는 이 선례가 없다 → 오버레이는 View 유지를 권한다.

**APK 크기 증가(대략)**: debug 기준 **2.9MB → 약 30~35MB**(Compose dex 약 +26MB + 글꼴 6.3MB). 참고 앱 debug 값에서 추정한 것이다. release + R8(`isMinifyEnabled = true`)이면 크게 줄지만 **실측값은 미확인**이다. 개인 사이드로드라 설치 크기 자체는 문제가 되지 않고, 설치 시간(adb install)이 몇 초 늘어나는 정도로 본다.

### 10-4. 장단

| | 기본 View 유지 | Compose 전환 |
|---|---|---|
| 좋은 점 | 의존성 0 · APK 2.9MB · 빌드 빠름 · 지금 코드(620줄) 그대로 · 오버레이·접근성 서비스와 같은 방식 | 참고 두 앱의 **부품을 그대로 복사**(팔레트·칩·말풍선·목록 줄·SplitPane·TextSize·아이콘·마크다운) · 테마 3상태·폴드 칸 나누기·sticky 머리·당겨서 새로 고침·애니메이션이 싸다 · 사용자가 이미 보고 합의한 디자인이 그대로 나온다 |
| 대가 | 채팅 목록·필터·폴드 2칸·다크/라이트를 전부 손으로 짜야 한다 · 참고 앱과 디자인을 맞추려면 부품을 View로 다시 만들어야 한다 | APK 약 10배(debug) · 첫 빌드 시간 증가 · MainActivity 재작성 · 오버레이에 쓰면 lifecycle 배선이 필요 |

### 10-5. 추천 — 섞어 쓴다
- **Compose로**: 대시보드·작업 기록 목록·대화 화면·설정(`MainActivity`) + **팝업 입력**(요구 ①을 투명/대화상자 테마의 별도 액티비티로 띄우는 경우 — ACTION_ASSIST·ROLE_ASSISTANT 경로는 인계 문서의 「실기 검증 필요」 가설).
- **View로 유지**: `Overlay.kt` 상단 패널 · 새로 만드는 **테두리 빛**(접근성 `TYPE_ACCESSIBILITY_OVERLAY`, 터치 통과). 이유: 오버레이는 액티비티 lifecycle 밖이고, 제스처 좌표 탭을 막지 않아야 하는 민감한 곳이라(`AgentService.gesture` 주석의 FLAG_NOT_TOUCHABLE 비동기 함정) 의존성을 늘리지 않는 편이 안전하다. 테두리 빛은 `View.onDraw`에서 가장자리 그라데이션(`LinearGradient`/`RadialGradient`) + `ValueAnimator` 알파면 충분하다.
- 색 토큰은 한 곳(`Palette.kt` 같은 object)에 두고 View 쪽은 같은 hex를 `Long` 상수로 읽게 해서 두 세계의 색이 어긋나지 않게 한다.

---

## 11. 재사용 코드 목록 (복사 후 패키지명만 바꾼다)

| 파일 | 가져올 것 | 손볼 것 |
|---|---|---|
| `A/core/src/main/java/kr/joonlab/core/Theme.kt` | `CorePalette`·`LocalPalette`·`ThemeMode`(3상태)·`Pretendard`·`CoreTheme`(상태 바 글자색 포함) | 패키지 · `R.font` 참조 |
| `A/core/src/main/res/font/pretendard_{regular,medium,semibold,bold}.otf` | 글꼴 | 굵기 2개로 줄일지 결정 |
| `A/history/src/main/java/kr/joonlab/cchistory/Palette.kt` | `object H : CorePalette` 틀 · `Hi()` 아이콘 · `hex()` | **색 값은 새로**(폴드 에이전트 팔레트) |
| `A/app/src/main/java/kr/joonlab/cmuxremote/Ui.kt` | `statusColor`·`statusTint`(상태 → 면 물듦) · `Modifier.card`(왼쪽 3dp 띠 + 테두리 + 물듦) · `Dot`·`Chip`·`Tag` · `ago()` · `StatusHead` | 상태 이름을 실행 상태(running·confirm·done·failed·cancelled)로 |
| `A/core/src/main/java/kr/joonlab/core/Split.kt` | `SplitPane`·`PaneState`·`rememberPaneState`·`WIDE`(600dp) | 그대로 |
| `A/core/src/main/java/kr/joonlab/core/TextSize.kt` | 「가」 버튼·핀치·`ScaledText`·`PinchBadge` | prefs 이름 |
| `A/core/src/main/java/kr/joonlab/core/Icons.kt` + `IconsGen.kt` + 생성기 `A/tools/gen_icons.py` | `strokeIcon`(SVG path → ImageVector 캐시) · `Lic` · 회전 스피너 | **mic·send·trash·archive·stop 아이콘이 목록에 없다** — 생성기에 추가해서 다시 만든다 |
| `A/history/src/main/java/kr/joonlab/cchistory/HeroGen.kt` + `A/tools/gen_heroicons.py` | Heroicons v1 outline 25개 path(search·folder·bookmark·calendar·tag·chevron·x·filter·chat·clock·play·terminal …) | 필요한 이름만 |
| `A/history/src/main/java/kr/joonlab/cchistory/ListUi.kt` | `Chip`(알약)·`TagChip`·`IconBtn`·`SearchField`(16sp 입력)·`FilterChips`·`SessionList`(PullToRefresh + sticky 날짜 머리)·`SessionRow`·`markSnippet`·위에서 덮는 `Sheet` | 데이터 모델을 작업 기록으로 |
| `A/history/src/main/java/kr/joonlab/cchistory/ConvUi.kt` | `rowsOf`(연속 도구 접기·머리 한 번) · `ChatRow` 말풍선 모양 · `ToolGroup` · `QuestionCard` · `SysChip`(점선) · `FindBar` · `FollowPill` · `toBottom()`(열자마자 맨 아래 — 프레임 대기 수정 포함) | 도구 → 에이전트 단계 |
| `A/app/src/main/java/kr/joonlab/cmuxremote/Answer.kt` | 끌어서 접는 아래 `Sheet`(손잡이) · `PrimaryButton` · `Field` · `Composer`(입력칸 + 원형 보내기) | 키 줄·스니펫 빼고 마이크 추가 |
| `A/app/src/main/java/kr/joonlab/cmuxremote/Tree.kt` | `DialogBox`(18dp) · `Buttons`(취소/확인 한 쌍) — 삭제 확인에 | |
| `A/core/src/main/java/kr/joonlab/core/Markdown.kt` | `MarkdownText`(에이전트 답이 마크다운일 때) | 필요할 때만 |
| `A/history/src/main/java/kr/joonlab/cchistory/Posture.kt` | `rememberHinge`(FoldingFeature + `TYPE_HINGE_ANGLE`, 0.5초 안정화) · `HingeBand` | 2단계(반접힘)에서만, `androidx.window` 필요 |
| `A/history/src/main/java/kr/joonlab/cchistory/MainActivity.kt` | 자세 분기(`BoxWithConstraints` 폭 → cover/port/land/table/book) · 커버 아래 탭 막대 `Tab` · `Stats`/`Stat`(사이드바 숫자) · 토스트 알약 | 뼈대 참고 |
| `A/history/src/main/java/kr/joonlab/cchistory/State.kt` | `shortTime`·`ago`·`dateLabel` 시간 표기 | |

가져오지 않을 것: `core/Agent.kt`(맥 에이전트 연결 — 폴드 에이전트와 무관), `Snippets.kt`(cmux 스니펫), `Resume.kt`·`Board.kt`·`Tree.kt`의 cmux 전용 로직.

---

## 12. 확인 못 한 것

- AGP 9.4.1 내장 Kotlin 버전(참고 앱이 compose 플러그인 2.4.20으로 빌드된다는 것만 확인)
- Compose 앱의 release(R8) APK 크기 — 참고 앱에 release 산출물이 없다
- 접근성 오버레이 안의 Compose 동작 — 참고 앱에 선례 없음
- 참고 앱의 웹 원형(`VIEWER-INVENTORY.md §3-3` 반경·간격 표)은 이 보고서에서 코드 값으로 대신했다(웹 값은 인벤토리 원문 참조)
