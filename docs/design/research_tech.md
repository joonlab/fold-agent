# 기술 조사 — ① 팝업 입력(측면 버튼) · ② 조작 중 테두리 빛

작성 2026-09-26 · 대상 `이 저장소`(Kotlin, 기본 View, minSdk 30 / targetSdk 36) · 폴드8 One UI 9 / Android 16
표기: **[확인]** = 소스·문서로 직접 봄(출처 붙임) · **[추정]** = 근거에서 추론 · **[미확인]** = 실기로 판정해야 함
폰(adb)에는 아무 명령도 보내지 않았다. 실기 항목은 끝의 「검증 계획」에 모았다.

로컬 SDK 에 `sources/` 는 없다(`$ANDROID_HOME/platforms/android-36/android.jar` 만). API 존재 여부는 이 jar 를 `javap`(openjdk@21)로 확인했다.

---

## ① 앱으로 이동하지 않는 팝업 입력

### 1-1. 기본 디지털 어시스턴트(ROLE_ASSISTANT)가 되는 조건 — [확인]

`AssistantRoleBehavior.getQualifyingPackagesInternal()` 이 **둘 중 하나**만 있으면 후보로 넣는다.

| 경로 | 조건 |
|---|---|
| A. VoiceInteractionService | 서비스 `permission="android.permission.BIND_VOICE_INTERACTION"` + intent-filter `android.service.voice.VoiceInteractionService` + meta-data `android.voice_interaction` xml 에 **`sessionService`·`recognitionService` 둘 다 있고 `supportsAssist="true"`**. `settingsActivity` 는 선택. 저RAM 기기는 이 경로 제외 |
| B. ACTION_ASSIST 액티비티 | `android.intent.action.ASSIST` + DEFAULT 카테고리로 잡히는 **exported** 액티비티 하나. VIS 없이도 역할 후보가 된다 |

- 출처: `PermissionController/role-controller/java/com/android/role/controller/behavior/AssistantRoleBehavior.java` (https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/role-controller/java/com/android/role/controller/behavior/AssistantRoleBehavior.java)
- `VoiceInteractionServiceInfo` 도 같은 조건으로 파싱 오류를 낸다: `No sessionService specified` / `No recognitionService specified` (https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/voice/VoiceInteractionServiceInfo.java)
- 역할 정의 `roles.xml`: `android.app.role.ASSISTANT` 는 **`requestable="false"`** — 앱이 `RoleManager.createRequestRoleIntent()` 로 팝업을 띄워 받을 수 없다. 사용자가 **설정 → 기본 앱 → 디지털 어시스턴트 앱**에서 직접 골라야 한다(또는 셸 `cmd role add-role-holder android.app.role.ASSISTANT kr.joonlab.foldagent` — 메인 세션이 **사용자에게 물은 뒤에만**). `fallBackToDefaultHolder="true"`·`showNone="true"` 라 해제하면 기본값(Gemini)으로 돌아간다 → 되돌리기 쉽다. (https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml)
- 역할 보유자에게 주는 권한 목록에 `sms` 세트·`READ_CALL_LOG`·SYSTEM_ALERT_WINDOW(app-op) 가 있다 [확인: roles.xml]. 역할 부여는 매니페스트에 **선언한 권한만** 준다고 알고 있으나 이 부분은 [미확인] — 우리 매니페스트엔 SMS·통화기록 선언이 없으니 영향 없을 것으로 [추정]. 설치 후 `dumpsys package` 로 확인할 것.
- 역할이 바뀌면 `VoiceInteractionManagerService.onRoleHoldersChanged()` 가 `Settings.Secure.ASSISTANT`·`VOICE_INTERACTION_SERVICE` 를 우리 VIS 로 쓴다(VIS 가 없으면 ASSIST 액티비티를 ASSISTANT 에 씀). **`VOICE_RECOGNITION_SERVICE`(시스템 기본 음성인식기)는 여기서 바꾸지 않는다** [확인] — 즉 우리가 형식상 선언하는 RecognitionService 가 다른 앱의 음성 입력을 가로채지 않는다. 주석상 「Android 11 이하에선 유효한 RecognitionService 여야 부트루프 방지」— 우리 기기(16)는 해당 없음. (https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java)
- 참고 샘플 매니페스트: https://android.googlesource.com/platform/development/+/refs/heads/main/samples/VoiceInteractionService/AndroidManifest.xml

최소 선언(스니펫):
```xml
<service android:name=".assist.FaVoiceService" android:exported="true"
    android:permission="android.permission.BIND_VOICE_INTERACTION">
  <intent-filter><action android:name="android.service.voice.VoiceInteractionService"/></intent-filter>
  <meta-data android:name="android.voice_interaction" android:resource="@xml/voice_interaction"/>
</service>
<service android:name=".assist.FaSessionService" android:exported="true"
    android:permission="android.permission.BIND_VOICE_INTERACTION"/>
<service android:name=".assist.FaRecognitionService" android:exported="true">   <!-- 형식 요건용 스텁 -->
  <intent-filter><action android:name="android.speech.RecognitionService"/></intent-filter>
</service>
<!-- res/xml/voice_interaction.xml -->
<voice-interaction-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:sessionService="kr.joonlab.foldagent.assist.FaSessionService"
    android:recognitionService="kr.joonlab.foldagent.assist.FaRecognitionService"
    android:settingsActivity="kr.joonlab.foldagent.MainActivity"
    android:supportsAssist="true"/>
```
스텁 RecognitionService 는 `onStartListening` 에서 곧바로 `error(ERROR_CLIENT)` 정도로 둔다(누가 명시적으로 부를 일은 없다).

### 1-2. 삼성 측면 버튼 「길게 누르기」가 서드파티 어시스턴트를 부르나

- **부른다 [확인·웹]**: ChatGPT 베타(1.2025.070)가 기본 디지털 어시스턴트 앱이 되자 갤럭시 측면 버튼 길게 누르기로 호출됐다(SamMobile 2025-03-17, https://www.sammobile.com/news/summon-chatgpt-side-button-galaxy-phones/ · 9to5Google 2025-03-14, https://9to5google.com/2025/03/14/chatgpt-default-assistant-android/). 기사 표현상 측면 버튼 선택지는 Bixby·「디지털 어시스턴트 앱」이고, 후자가 역할 보유자를 따른다. Perplexity 도 같은 방식(삼성 지원 문서 https://www.samsung.com/us/support/answer/ANS10010357/ — 검색 요약으로만 봄, 페이지 본문은 [미확인]).
- 9to5Google: ChatGPT 는 처음엔 앱 전체가 열렸다가 오버레이로 바뀌었다 → **활동(액티비티)을 여는 구현이면 「앱으로 이동」처럼 보인다**는 반례. 세션 창(1-3 a)으로 가야 Gemini 같은 느낌이 난다 [추정].
- 삼성이 내부적으로 VIS `showSession` 을 부르는지, ASSIST 액티비티를 여는지(=VIS 가 있으면 VIS 우선인지)는 **[미확인]** — AOSP 경로(PhoneWindowManager 길게 누르기 → StatusBar `startAssist` → VIS 있으면 세션, 없으면 ACTION_ASSIST)와 같다고 [추정]. 실기 1번 항목.
- One UI 9 측면 버튼 설정 화면: `com.samsung.android.intent.action.SIDE_KEY_SETTINGS`(인계 문서, 실기 검증됨). 「길게 누르기」 = 디지털 어시스턴트 / 전원 끄기 메뉴 중 선택 — **설정 변경은 사용자에게 물은 뒤.**

**대안 「두 번 누르기 → 앱 열기」** (역할 변경 없이, 지금 README 에 이미 연결됨)
- 지금은 `MainActivity`(singleTask, 채팅 화면)가 열려 「앱으로 이동」이 된다.
- 별도 **투명 팝업 액티비티**를 런처 항목으로 하나 더 두면(`QuickAskActivity`: `Theme.Translucent.NoTitleBar` 또는 floating 대화상자 테마, `taskAffinity="kr.joonlab.foldagent.ask"`, `excludeFromRecents`, `noHistory`, `launchMode=singleInstance`, 두 번째 LAUNCHER intent-filter + 다른 label 「에이전트 빠른 입력」) 삼성 「앱 열기」 목록에서 따로 고를 수 있을 것 [추정 — 목록이 런처 액티비티 단위인지 앱 단위인지 미확인]. 앱 단위면 activity-alias 로도 안 되니, 그땐 MainActivity 가 `sideKey` 인텐트를 받아 곧바로 팝업 액티비티로 넘기는 방식(한 번 깜빡임).
- 이 방식은 뒤 앱이 `onPause` 되지만 투명이라 화면은 그대로 보이고, `finish()` 하면 원래 앱이 앞에 남는다(ClipReadActivity 와 같은 원리, 이미 실기에서 쓰는 패턴).
- 셋째 트리거 후보: 접근성 버튼/단축키(`FLAG_REQUEST_ACCESSIBILITY_BUTTON`, 내비게이션 바 버튼 또는 볼륨 두 키 길게) · 퀵설정 타일(README 「다음」에 있음). 접근성 서비스는 전원 키 이벤트를 받을 수 없으므로 측면 버튼 자체를 가로채는 길은 없다 [추정 — 전원 키는 정책 계층에서 소비].

### 1-3. 팝업 UI 구현 후보 비교

| | (a) VoiceInteractionSession 자체 창 | (b) 세션 → 투명 「어시스턴트 액티비티」 | (c) 접근성 오버레이에 입력창 |
|---|---|---|---|
| 창 | `TYPE_VOICE_INTERACTION`, `VoiceInteractionWindow`(Dialog 계열), MATCH_PARENT, `Gravity.BOTTOM`, `FLAG_LAYOUT_IN_SCREEN`·HW 가속 [확인: VoiceInteractionSession.java `ensureWindowCreated`] | `startAssistantActivity(intent)` — 새 태스크, **멀티윈도우 영향 없는 별도 레이어**에 뜸, 거기서 시작한 태스크는 일반 레이어로 감 [확인: API 문서]. `onPrepareShow` 에서 `setUiEnabled(false)` 로 세션 창 생략 | `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE` 을 빼야 입력 가능 |
| 키보드 | Dialog 라 포커스 가능 → EditText 에 IME 연결될 것 [추정·강함] | 일반 액티비티 — 확실 | **불확실** [미확인]. 접근성 오버레이 층은 IME 층보다 위라 키보드가 카드 **아래**로 깔리고 IME 인셋 자동 조정도 없다 [추정] |
| 음성 | 세션은 앱 프로세스에서 돈다. 세션 표시 중 앱이 「사용 중(foreground)」으로 인정돼 마이크/SpeechRecognizer 가 되는지 **[미확인]** | 앞에 보이는 액티비티 → while-in-use 충족, 지금 MainActivity 의 인식 코드 그대로 이식 가능 | 접근성 서비스 프로세스만으로 마이크 허용되는지 [미확인] |
| 현재 앱 위 | 그렇다. 뒤 앱은 **resume 상태 유지**(창만 위에) [추정] | 그렇다(투명). 뒤 앱은 onPause | 그렇다 |
| 닫은 뒤 | `hide()`/`finish()` → 원래 앱이 그대로 앞 | `finish()` → 원래 앱이 앞(assistant 레이어가 빠짐) [추정] | 창 제거 → 그대로 |
| 측면 버튼 | 직접 연결 | 직접 연결(세션 경유) | 불가(별도 트리거 필요) |
| 덤 | `onHandleAssist(AssistState)` 로 **지금 화면의 AssistStructure·스크린샷**을 받을 수 있다(사용자가 설정에서 허용 시) — 「이 화면에서 …」 요청의 맥락 [확인: API 문서 SHOW_WITH_ASSIST/SCREENSHOT] | 동일 | 없음 |
| 신뢰 창 | `TYPE_VOICE_INTERACTION` 은 trusted overlay [확인: InputMonitor.isTrustedOverlay] | 일반 액티비티 | trusted |

**에이전트가 그 뒤 화면을 조작할 때 공통 함정**
1. **보내기 = 팝업을 먼저 완전히 닫고 나서 `startTask`.** 세션 창/팝업 액티비티가 떠 있으면 `rootInActiveWindow` 가 우리 창이 되고(ScreenReader 19행), `Agent.kt:841` 의 `svc.windows.forEach` 가 우리 입력창까지 읽는다. 또 전체 화면 세션 창은 기본 touchable insets 가 `TOUCHABLE_INSETS_FRAME` 이라 **좌표 탭을 먹는다** [확인: onComputeInsets 문서].
   → 순서: `hide()`(또는 `finish()`) → 윈도우 변화 이벤트(`TYPE_WINDOWS_CHANGED`) 로 우리 창이 목록에서 사라진 걸 확인(최대 ~1초) → `AgentService.startTask(text, meta{source:"popup"})`. 세션 → 접근성 서비스 전달은 같은 프로세스라 `AgentService.instance` 직접 호출로 충분.
2. 세션 창을 띄운 채 에이전트가 돌아야 한다면(예: 진행 상황을 팝업에 보이기) `onComputeInsets` 에서 `touchableInsets = TOUCHABLE_INSETS_REGION` + 카드 영역만 `touchableRegion` 으로 줘서 나머지를 통과시켜야 한다. **권장하지 않음** — 진행 표시는 기존 상단 패널 + 테두리 빛이 맡는다.
3. 확인 게이트(`confirm()`)는 그대로 오버레이 패널에서 묻는다 — 팝업 UI 를 바꿔도 이 흐름·40초 거부는 건드리지 않는다.
4. 키보드: 팝업을 닫을 때 IME 를 명시적으로 내린다(`hideSoftInputFromWindow`). 남아 있으면 첫 스와이프가 키보드 위를 지나간다(기존 사고). `keyboardShown()` 체크가 이미 있으니 시작 전 한 번 더 확인.
5. 접힘/펼침 전환 중 세션 창은 재배치된다(MATCH_PARENT) — 카드 폭은 `min(화면폭*0.92, 560dp)` 규칙(Overlay 와 동일)으로.

**추천안: (a) VoiceInteractionSession 자체 창 + 실패 시 (b) 로 즉시 폴백하는 1개 세션 구현**
- 이유: 측면 버튼에 직접 연결되고, 뒤 앱을 멈추지 않으며(Gemini 와 같은 모델), trusted 창이라 입력 차단 문제도 없다. ChatGPT 처럼 「앱이 잠깐 열리는」 인상을 피한다.
- 폴백 스위치: 실기에서 **(1) 키보드가 카드 위로 올라오는지 (2) 세션 표시 중 SpeechRecognizer 가 오디오를 받는지** 둘 중 하나라도 안 되면, 같은 세션에서 `setUiEnabled(false)` + `startAssistantActivity(QuickAskActivity)` 로 전환(코드 경로 하나 추가, UI 레이아웃은 공유).
- 역할을 안 바꾸기로 결정되면 (b)의 `QuickAskActivity` 를 「두 번 누르기 → 앱 열기」에 연결 — 같은 레이아웃 재사용. 즉 **입력 UI 는 View 하나(`QuickAskView`)로 만들고 세션 창/액티비티 어디에나 꽂는다.**
- 백그라운드 액티비티 시작: 우리 앱은 시스템이 바인딩한 접근성 서비스가 있어 BAL 예외 대상(기존 `showChat()` 이 그 근거로 동작 중). VIS 도 세션 표시 중엔 `startAssistantActivity` 로 합법적으로 띄운다. 즉 (b)·폴백 모두 BAL 걱정 없음 [추정 — showChat 실동작이 근거].

---

## ② 조작 중 화면 테두리 빛

### 2-1. 창 구성(추천)

접근성 서비스 컨텍스트의 `WindowManager` 로 **두 번째 오버레이 창**(`EdgeGlow`)을 만든다. 기존 상태 패널과 별개 창 — 패널은 터치를 받아야 하고, 빛은 **처음부터 끝까지 한 번도 터치를 받지 않아야** 하기 때문.

```kotlin
val lp = WindowManager.LayoutParams(
    MATCH_PARENT, MATCH_PARENT,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
).apply {
    layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS   // API 30 [확인: jar]
    setFitInsetsTypes(0)                                                // API 30 [확인: jar]
    setCanPlayMoveAnimation(false)                                      // 크기 변화 때 미끄러지는 애니메이션 방지 [확인: jar, API 33+ → 버전 분기]
    title = "FoldAgentEdgeGlow"
}
```
- 빛 창을 **패널보다 먼저 add** 하면 같은 타입 안에서 패널이 위에 온다(나중에 붙인 창이 위) [추정 — 같은 레이어 내 add 순서].
- `FLAG_NOT_TOUCHABLE` 은 **생성 시 고정, 절대 토글하지 않는다.** `AgentService.gesture` 주석(09-26 run 172029-564)이 보여 주듯 `updateViewLayout` 은 비동기라 토글 창은 제스처와 경주한다. 빛 창은 경주할 일이 없게 만든다.

### 2-2. 좌표 탭·스와이프를 막지 않는가 — [확인] 근거 3겹

1. **히트 테스트**: InputDispatcher 는 `NOT_TOUCHABLE` 창을 터치 대상으로 고르지 않는다(`InputDispatcher.cpp` 534·574행 부근). `dispatchGesture` 는 입력 주입이라 실제 손가락과 같은 경로로 창을 고른다 — 그래서 **touchable 패널에는 탭이 떨어졌던 것**(기존 사고)이고, 같은 이유로 NOT_TOUCHABLE 창은 통과한다. (https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/inputflinger/dispatcher/InputDispatcher.cpp)
2. **Android 12 「신뢰할 수 없는 터치 차단」**: 예외 목록에 「Trusted windows — Accessibility windows, IME windows, Assistant windows」가 명시돼 있다(https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events). 코드상으로도 `InputMonitor.isTrustedOverlay()` 가 `TYPE_ACCESSIBILITY_OVERLAY`·`TYPE_VOICE_INTERACTION` 을 trusted 로 분류한다(https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/InputMonitor.java 645행).
3. **「가려짐」 플래그(FLAG_WINDOW_IS_OBSCURED / PARTIALLY_OBSCURED)**: `canBeObscuredBy()` 는 위 창이 `TRUSTED_OVERLAY` 면 false 를 돌려준다(InputDispatcher.cpp 2797~2826행). → **`filterTouchesWhenObscured` 를 쓰는 은행·권한 대화상자·설치 확인 화면도 빛 때문에 탭을 버리지 않는다.** 이게 가장 중요한 확인이다: 전체 화면을 덮는 창이 untrusted 였다면 이런 화면에서 탭이 조용히 무시됐을 것.
   - 단 삼성이 이 경로를 고쳤는지는 [미확인] → 실기 항목 4.

### 2-3. 그리기·성능·배터리

- **그림은 한 번만 그리고, 숨쉬기는 알파로만.** 커스텀 View `onDraw` 에서 네 변을 `LinearGradient`(가장자리 붉은색 α≈0.55 → 안쪽 투명, 두께 18~28dp) + 네 모서리를 `RadialGradient`(반경 = 화면 둥근 모서리 반경 + 두께)로 채운 경로를 그린다. 크기가 바뀔 때만 Shader 재생성.
- 애니메이션: `ObjectAnimator.ofFloat(view, View.ALPHA, 0.25f, 0.9f)`, `repeatMode=REVERSE`, `repeatCount=INFINITE`, 반주기 1.6~2.0s, `PathInterpolator(0.4,0,0.2,1)`(숨쉬기). View.ALPHA 는 RenderNode 속성이라 **display list 재기록 없이 RenderThread 가 합성만 다시 한다** [추정·일반 원리].
  - `override fun hasOverlappingRendering() = false` — 알파 적용 시 오프스크린 레이어 생성을 피한다(그라데이션끼리 겹침은 모서리 몇 픽셀뿐이라 차이 미미).
  - 대안으로 `setLayerType(LAYER_TYPE_HARDWARE)` 로 한 번 텍스처화 후 알파 — 둘 중 GPU 프로파일로 고른다.
- **RenderEffect(블러) 불필요**: 그라데이션 자체가 부드럽다. 블러는 매 프레임 비용이 커서 배터리에 불리.
- **주사율**: 연속 애니메이션은 LTPO 패널이 저주사율로 못 내려가게 한다. Android 15+ `View.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_LOW)` [확인: jar에 존재] 를 빛 View 에 걸어 30Hz 급으로 요청 — 숨쉬기엔 충분. 실제 반영 여부는 [미확인](`dumpsys SurfaceFlinger` 로 확인).
- 켜진 시간만 비용 — 작업이 끝나면 `animator.cancel()` + `removeView`(창 제거)까지. 창을 INVISIBLE 로 두고 남겨 두면 합성 레이어는 사라지지만 창 객체는 남는다; 작업 사이 간격이 길면 제거가 낫다.
- 톤 제안: `#FF3B30` 계열이 아니라 조금 따뜻하고 탁한 `#E5484D`~`#FF5A5F`, 가장자리 α 최대 0.55, 켜질 때 300ms 페이드인·꺼질 때 450ms 페이드아웃(갑자기 사라지지 않게). 다크/라이트 배경 모두에서 보이게 가장자리 1dp 에 α 0.8 선을 얹는 안도 시안으로.

### 2-4. 디스플레이 모서리·컷아웃·접힘/펼침

- 둥근 모서리: `WindowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT…)` [확인: jar, API 31+]. minSdk 30 이므로 `Build.VERSION.SDK_INT >= 31` 분기, 30 이면 고정 24dp. `getDisplayShape()`(API 34+) [확인: jar] 로 실제 외곽 Path 를 받으면 가장 정확 — 삼성이 채워 주는지 [미확인].
- 값은 `view.setOnApplyWindowInsetsListener` 로 받아 두고, 인셋이 바뀌면(접힘↔펼침, 회전) Shader·Path 재계산. 커버 1248x1972 ↔ 메인 2448x1848 에서 모서리 반경이 다르다(README 해상도).
- 컷아웃(펀치홀·UDC)은 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 로 무시하고 그 위까지 그린다 — 빛이 카메라 구멍을 피해 끊기면 더 어색하다.
- 접힘/펼침: 창이 MATCH_PARENT 라 자동으로 새 크기를 받는다 [추정]. 단 `svc.resources.displayMetrics` 는 서비스 컨텍스트 기준이라 늦게 갱신될 수 있으니 빛 View 는 **자기 `onSizeChanged` 의 w/h 만** 쓴다. 전환 순간 한 프레임 늘어져 보이는 건 `setCanPlayMoveAnimation(false)` 로 줄인다.
- 전환 중 접근성 연결이 흔들리면 `BadTokenException` 가능 — 패널과 같은 `try/catch` 패턴(오버레이만 건너뜀).

### 2-5. 스크린샷·capture·look 에 빛이 찍히지 않게

- `takeScreenshot(DEFAULT_DISPLAY)` 는 화면 합성 전체라 접근성 오버레이도 찍힌다(그래서 기존 코드가 패널을 숨김) [추정·기존 코드 근거]. 공개 API 에 「이 창은 스크린샷 제외」는 없다 — `SurfaceControl.Transaction` 공개 메서드에 skip-screenshot 류 없음 [확인: jar]. `FLAG_SECURE` 는 오히려 화면 전체를 검게 만들 위험이라 금지.
- **기존 `overlay.setVisible(false)` 지점 3곳(`screenshotJpegBase64`·`captureBitmap`·`systemScreenshot`)에 빛도 함께 숨긴다.** 숨김은 `view.visibility = INVISIBLE`(알파 애니메이터는 계속 돌려도 됨, 보일 때 자연스럽게 이어짐). 빛 창이 INVISIBLE 이면 입력 쪽에서도 `NOT_VISIBLE` 로 취급된다(canBeObscuredBy 첫 분기).
- 150ms 대기가 부족할 수 있다 — 숨김 후 `view.post{}` 가 한 프레임 지난 뒤 `Choreographer.postFrameCallback` 두 번째 콜백에서 latch 를 풀어 **실제로 숨겨진 프레임 이후**에 찍는 편이 확실. 판정은 `look` 결과 JPEG 가장자리 픽셀 평균 붉은기로 자동 점검 가능(검증 계획 5).
- 대안 `takeScreenshotOfWindow(windowId)`(API 34) [확인: jar] 는 한 창만 찍어 오버레이가 안 들어가지만, 상태바·키보드·다른 창이 빠져 `look` 의 「보이는 그대로」와 달라진다 → **기본은 숨김 방식 유지**, 단일 앱 캡처(`capture` 부분 캡처)에서만 후보.
- 사용자 입장: 찍는 0.3초 동안 빛이 깜빡 꺼진다 — 「찰칵」처럼 읽히게 페이드 없이 즉시 끄고 페이드로 켜면 오히려 자연스럽다(디자인 결정 사항).

### 2-6. 켜짐/꺼짐 시점 제안

| 상태 | 표현 | 이유 |
|---|---|---|
| 작업 시작~끝(`running != null`) 중 **화면을 건드리는 동안** | 붉은 빛 숨쉬기(주기 ~3.5s) | 「지금 폰을 AI 가 만지고 있다 — 손대지 말 것」이 핵심 의미 |
| 모델 생각 중·API 대기(화면 조작 없음) | 빛 유지하되 진폭을 줄임(α 0.25↔0.45) 또는 유지 | 단계마다 켰다 껐다 하면 번쩍임이 거슬린다. **작업 단위로 켜고, 강약만 조절**을 권장 |
| 확인 대기(`confirm()` 40초) | 숨쉬기 멈추고 **호박색(앰버) 고정 α 0.5** 또는 붉은 빛을 느린 2회 펄스 후 고정 | 「AI 가 멈추고 당신을 기다린다」는 다른 신호. 발송·결제·삭제 게이트가 눈에 띄어야 한다(게이트 약화 금지 원칙과 일치) |
| 사용자가 정지/취소 | 즉시 450ms 페이드아웃 | |
| 끝(성공/실패) | 페이드아웃, 👍👎 는 패널이 담당 | |
| 대화형 답만(도구 0개) | 켜지 않음 | 화면을 안 만졌으므로 |

구현 연결점: `AgentService.startTask` 에서 `glow.start()`, 에이전트 스레드 `finally` 에서 `glow.stop()`, `confirm()` 진입/퇴장에서 `glow.mode(WAIT)`/`glow.mode(ACT)`. 전부 `main.post`. 접근성 `onUnbind` 에서 제거.
사용자가 작업 중 화면을 직접 만지면(`input_interaction`) 빛을 잠깐 밝게 한 번 → 「지금 에이전트가 조작 중」 경고로 쓸 수 있다 — 선택 사항 [추정: 접근성으로 사용자 터치 감지는 `TYPE_TOUCH_INTERACTION_START` 이벤트 필요, 현재 이벤트 타입엔 없음].

---

## 검증 계획(메인 세션이 폰에서 직렬로 — 시작 전 `input_interaction` 확인, 스크린샷은 `/data/local/tmp`)

| # | 확인할 것 | 방법 | 통과 기준 |
|---|---|---|---|
| 1 | 측면 버튼 길게 → 우리 VIS 세션이 뜨나(ASSIST 액티비티가 아니라) | 사용자 승인 후 역할 변경(설정 화면 또는 `cmd role add-role-holder`), 측면 버튼 길게는 **사용자가 누름**. `dumpsys voiceinteraction` 로 현재 interactor 확인. 로그 `onShow flags=` | 현재 앱이 그대로 보이고 하단 카드가 뜬 캡처 |
| 2 | 세션 창에서 키보드 | EditText 탭 → 캡처 | 키보드가 카드 **아래**에 있고 카드가 가려지지 않음 |
| 3 | 세션 창에서 음성 | 마이크 버튼 → `onReadyForSpeech`/`onRmsChanged` 로그 | 부분 결과가 카드에 뜸. 실패 시 (b) 폴백 |
| 4 | 빛 창이 탭을 막지 않나 | 빛 켠 상태로 `./dev.sh ask` 좌표 탭 요청 + **`filterTouchesWhenObscured` 화면**(예: 설정 → 앱 → 권한 대화상자, 또는 설치 확인) 탭 | 탭이 먹고, `logcat -d` 에 `Untrusted touch due to occlusion` 없음 |
| 5 | look/capture 에 빛 안 찍힘 | 작업 중 capture → 저장 JPEG 가장자리 16px 평균 R-G 차 | 빛 없는 기준 캡처와 차이 < 임계값 |
| 6 | 접힘↔펼침 | **사용자가 펼침** 후 빛 캡처 2장 | 모서리 곡률이 화면과 맞고 늘어짐 없음 |
| 7 | 배터리/주사율 | 빛 켠 채 5분, `dumpsys gfxinfo kr.joonlab.foldagent`, `dumpsys SurfaceFlinger` 주사율 | 프레임 드롭 없음, 가능하면 저주사율 요청 반영 |
| 8 | 팝업 닫힘 → 에이전트 시작 순서 | 팝업에서 요청 → 첫 단계 화면 목록에 우리 창 텍스트가 없는지(런 기록 jsonl) | 첫 화면이 원래 앱 |
| 9 | 역할 되돌리기 | 역할 해제 → Gemini 복귀 확인 | 측면 버튼이 Gemini 로 |

⛔ 실행 중 `uiautomator dump` 금지(1·8번에서 창 확인은 런 기록·`dumpsys window windows | grep FoldAgent` 로).

## 결정이 필요한 것(AskUserQuestion 후보)
1. 기본 디지털 어시스턴트를 폴드 에이전트로 바꿀지(측면 버튼 길게 = Gemini 를 잃음 / 해제하면 복귀) — 아니면 「두 번 누르기 → 빠른 입력」만.
2. 확인 대기 중 빛 표현: 앰버 고정 vs 붉은 빛 느린 펄스 vs 빛 끄고 패널만.
3. 빛 색·두께·주기 시안(3안 비교 캡처).
4. 팝업을 열 때 현재 화면 내용(AssistStructure/스크린샷)을 에이전트 맥락으로 넘길지 — 개인정보 측면.
