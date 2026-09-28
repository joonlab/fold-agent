package kr.joonlab.foldagent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kr.joonlab.foldagent.jobs.JobNotify
import kr.joonlab.foldagent.jobs.Jobs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 요청 하나를 끝까지 수행하는 루프 — Claude Code 의 에이전트 루프와 같은 모양이다.
 *   화면 읽기 → 모델이 도구 하나 고름 → 실행 → 바뀐 화면을 결과로 돌려줌 → 반복
 *
 * 매번 더 잘하게 만드는 장치:
 *   - 시스템 프롬프트에 knowledge.md(다듬은 노트) + learned(폰이 배운 것) + 설치 앱 목록
 *   - 비슷한 과거 성공 경로를 「참고」로 준다(명령이 아니라 참고 — 화면이 다르면 모델이 판단)
 *   - 실행 중 배움: 앱 별칭 · 접근성 클릭을 무시하는 앱
 *   - 모든 것을 TraceLog 에 남긴다 → 맥에서 분석해 knowledge.md 를 개정
 *
 * 확인 게이트는 모델의 판단(irreversible)과 코드 규칙(isRiskyAction — 실행 버튼 판정) 두 겹이다.
 */
class Agent(
    private val svc: AgentService,
    private val task: String,
    private val meta: JSONObject,
    private val session: Session,
) {

    companion object {
        private const val TAG = "FoldAgent"
        // 단계 한도는 두지 않는다 — 이상 징후(무효 반복·왕복·실패 연속·오류)로만 멈춘다.
        // HARD_CAP 은 감시를 모두 빠져나간 폭주에 대비한 비상 상한일 뿐, 정상 작업이 닿을 값이 아니다.
        private const val HARD_CAP = 150
        private const val FAIL_STREAK_STOP = 4
        private const val SCREEN_MARK = "[현재 화면]"
        /** 폰 화면을 보거나 만지지 않는 도구 — 앱 안에서 시킨 일이 이것만 쓰면 채팅 화면을 물리지 않는다(손전등·음량 등은 앱에 머문 채) */
        // 홈 비서 도구도 화면을 안 쓴다(계약 §0-7) — 대화 중 앱이 홈으로 나가지 않게
        /** 첫 도구로 오면 방법 고르기를 한 번 떠올리게 하는 도구 — 길을 정해 버리는 것들 */
        private val WAY_GATE_TOOLS = setOf("web_search", "skill_search", "claude_task")
        /** 알림·문자처럼 남의 말(인증번호·대화)을 읽는 도구 — 결과 본문을 trace·세션에 남기지 않는다(첫 줄 = 건수·조건만). 화면도 안 쓴다 */
        val PRIVATE_READ_TOOLS = setOf("notifications", "sms_read")
        private val NO_SCREEN_TOOLS = setOf("note", "list_apps", "geocode", "media", "volume", "flashlight", "brightness", "sound_mode", "clipboard", "wait", "trash_captures", "system_setting", "choose_way", "save_to_downloads") + PRIVATE_READ_TOOLS + AssistantTools.NAMES +
            OaiTools.NAMES + CapTools.NAMES   // 직접 도구·스킬/실행/MCP 도 화면을 안 쓴다(계약 cc-capabilities §0-6)
        /** 폰 화면을 전혀 바꾸지 않는 원격 도구 — acted(화면 조작) 판정에서 뺀다. 이것만 쓴 턴은 채팅을 다시 띄워 답을 보인다 */
        private val REMOTE_TOOLS = AssistantTools.NAMES + OaiTools.NAMES + CapTools.NAMES
        /** 스킬·실행 op 별 폰 쪽 읽기 시간초과. run 동기는 서버가 90초에 job 으로 넘기므로(계약 §1-2) 그보다 넉넉히 */
        private val CAP_TIMEOUT_MS = mapOf(
            "skill_search" to 25_000, "skill_view" to 25_000, "run" to 110_000, "code_run" to 45_000,
            "mcp_tools" to 30_000, "mcp_call" to 100_000, "job_status" to 25_000, "job_cancel" to 25_000, "claude_task" to 30_000,
        )
        private const val CAP_BACKGROUND_TIMEOUT_MS = 30_000   // background:true 는 job 만들고 바로 돌아온다
        private const val MAX_ATTACHMENTS = 30
        /** 비서 op 별 폰 쪽 읽기 시간초과 — 서버 시간초과(계약 §2-4)에 10초 여유. 서버가 먼저 timeout(504)을 돌려주게 한다 */
        private val ASSISTANT_TIMEOUT_MS = mapOf(
            "agenda" to 40_000, "brief_latest" to 30_000, "mail_triage" to 100_000, "mail_awaiting" to 100_000, "mail_search" to 40_000,
            "mail_read" to 40_000, "memory_search" to 25_000, "memory_get" to 25_000, "memory_add" to 25_000,
            "event_create" to 40_000, "event_delete" to 40_000, "mail_send" to 40_000, "mail_reply" to 70_000,
        )
        private const val WARN_REPEATS = 3   // 효과 없는 같은 동작이 이만큼이면 모델에게 경고
        private const val STOP_REPEATS = 5   // 이만큼이면 중단
        // 확인 게이트: 「그 버튼을 누르는 것 자체가 발송·송금·결제·삭제를 실행하는가」만 본다.
        // 예전엔 이름 어디에든 단어가 있으면 물어서, 노트 제목·거래 목록 줄처럼 내용에 「송금」이 섞인 항목을 열 때도 물었다(2026-09-26).
        // 실행 버튼은 이름이 짧고 동사로 끝난다: 「1 보내기」「송금」「결제하기」「메시지 보내기」「삭제」, 토스 「송금, 토스뱅크 통장…」(첫 마디).
        // 그래서 이름을 쉼표·가운뎃점·줄바꿈으로 나눈 앞 두 마디 중 짧고(≤14자) 위험 동사로 끝나는 것이 있을 때만 확인한다.
        private val SETTINGS_ALIAS = mapOf(
            "BATTERY_SETTINGS" to "com.samsung.android.sm.ACTION_BATTERY",
            "POWER_USAGE_SUMMARY" to "com.samsung.android.sm.ACTION_BATTERY",
            "BATTERY_USAGE" to "com.samsung.android.sm.ACTION_BATTERY",
            // 설정 검색 — 이 폰에서 둘 다 com.android.settings.intelligence SearchActivity 로 열린다(2026-09-27). SEARCH_SETTINGS 는 구글 앱 설정이라 막는다
            "SETTINGS_SEARCH" to "android.settings.APP_SEARCH_SETTINGS",
            "SEARCH_SETTINGS" to "android.settings.APP_SEARCH_SETTINGS",
        )
        // 동작을 시키는 요청 — 도구 없이 성공으로 끝내면 한 번 되묻는다(아래 zeroStepChallenge)
        // 「안 꺼지게 해 줘」「5분으로 바꿔 줘」도 동작 요청 — 없으면 도구 없이 「켜 두었습니다」가 ok 로 남는다(2026-09-27 system_setting 검토).
        // 「해 줘」는 「~게/~로」와 묶는다(맨 「해 줘」는 질문·잡담까지 잡는다). 맨 「꺼지게」는 「몇 분 뒤 꺼지게 돼 있어?」를 잡아 뺐다
        private val ACTION_REQ = Regex("(만들|만들어|써 ?줘|쓰고|적어|추가|붙여|넣어|보내|저장|캡처|찍어|켜 ?줘|꺼 ?줘|맞춰|열어|띄워|틀어|재생|공유|정리해|바꿔 ?줘|바꿔 ?주|게 ?해 ?줘|게 ?해 ?주|게 ?해 ?놔|로 ?해 ?줘|켜 ?둬|켜 ?놔)")
        private const val FALLBACK_CHALLENGE = "확인 필요: 지금까지 화면 없는 도구만 쓰고 요청을 못 채웠다. 끝내기 전에 앱 화면으로 한 번 더 해 본다 — " +
            "그 일을 하는 앱을 열어 검색·목록에서 찾는다(예: 문자 = 메시지 앱에서 이름 검색 · 번호 = 연락처 앱 · 설정 = 설정 검색 · 파일 = 내 파일). " +
            "사용자에게 정보를 더 달라고 묻기 전에 폰에서 찾을 수 있는 건 찾는다. 금지된 일이거나 화면으로도 안 되는 게 분명하면 그 이유로 다시 finish"
        private const val ZERO_STEP_CHALLENGE = "확인 필요: 이번 요청에서 아직 아무 도구도 실행하지 않았다. 지금 화면은 이전 작업이 남긴 것일 수 있다(그걸 이번 결과로 보고하면 거짓 성공이다). " +
            "새로 만들기·쓰기·캡처·보내기처럼 해야 하는 일이면 지금 도구로 실행한다. 요청한 상태가 정말 이미 되어 있으면 finish 메시지에 「이미 ~ 상태」라고 근거와 함께 다시 말한다."
        // 앱 사용 중지·제거도 실행 버튼이다 — 런처 길게 누르기 팝업 「사용 중지」가 게이트에 안 걸렸다(2026-09-28). 「홈 화면에서 제거」도 함께 걸린다(묻는 쪽이라 둔다)
        private const val VERB = "(보내기|전송|송금|이체|결제|구매|주문|삭제|지우기|탈퇴|해지|통화|전화 ?걸기|발송|제출|사용 ?중지|제거|uninstall|disable|send|pay|purchase|buy|delete|remove|order)"
        private val ACTION_LABEL = Regex(
            "^(\\d+\\s*(개|건|명|장)?\\s*)?([가-힣A-Za-z ]{0,8}\\s)?$VERB(하기|하세요|할게요)?\\s*(\\(?\\d+\\)?)?$",
            RegexOption.IGNORE_CASE
        )

        fun isRiskyAction(label: String): Boolean =
            label.split(Regex("[,·\\n]")).map { it.trim() }.filter { it.isNotEmpty() }.take(2)
                .any { it.length <= 14 && ACTION_LABEL.matches(it) }

        private val BASE_PROMPT = """
            너는 사용자의 안드로이드 폰(갤럭시 Z 폴드)을 대신 조작하는 음성 비서다. 사용자는 말로 요청했다(음성 인식이라 오타가 있을 수 있다).
            규칙:
            - 매 턴 도구를 정확히 하나만 호출한다. 도구 결과에 바뀐 화면이 온다.
            - 화면 항목은 [번호]로 가리킨다. 번호는 가장 최근 화면의 것만 유효하다.
            - 앱을 열 땐 open_app 을 쓴다. 이름은 아래 「설치된 앱」 목록의 표기 그대로 쓰면 가장 정확하다.
            - 알람은 set_alarm, 타이머는 set_timer 로 바로 맞춘다(시계 앱을 조작하지 않는다).
            - 시스템 기능은 전용 도구로 한 번에 한다: 시스템 스크린샷·화면 잠금 = global, 음악·영상 재생/멈춤/다음 = media, 음량 = volume, 밝기 = brightness, 소리/진동/무음·방해금지 = sound_mode, 손전등 = flashlight, 특정 앱의 앱 정보·알림 설정 = open_settings(page, package), 캘린더 새 일정·알람 목록·QR·동영상 촬영 = open_screen, 설정값 읽기·충전 중 화면 켜짐·화면 꺼짐 시간 = system_setting(설정 화면에 항목이 안 보여도 값은 있다 — 화면을 뒤지기 전에 get), 자동 회전 켜기/끄기 = quick_toggle(auto_rotate) 먼저, 타일이 없거나 실패하면 system_setting. 알림창·퀵 설정을 뒤져 같은 버튼을 찾지 않는다.
            - 와이파이·비행기 모드·모바일 데이터를 끄는 일은 하지 않는다 — 끄는 순간 이 비서의 인터넷 연결이 끊겨 멈춘다. 요청받으면 그 이유를 말하고 사용자가 직접 하게 finish 한다.
            - 요청에 맞는 도구가 없고 바로가기·[앱 요령]에도 방법이 없으면, 화면을 뒤지며 흉내 내지 말고 finish(success=false)로 「그 기능은 아직 할 수 없다」고 말한다.
            - 딥링크에 좌표가 필요하면 geocode 로 구한다. 좌표를 기억으로 지어내지 않는다(틀린 곳으로 경로가 잡힌다).
            - 딥링크(open_link)나 설정 바로가기(open_settings)로 목적 화면에 바로 갈 수 있으면 그게 가장 빠르다. 안 되면 화면을 조작한다.
            - open_link·open_settings 는 앱을 직접 연다 — 그 앞에 open_app·list_apps 를 부르지 않는다. 도구는 화면이 잠잠해진 뒤 결과를 주므로 곧바로 wait 하지 않는다(화면에 로딩 표시가 보일 때만).
            - 앱에 처음 들어가면 도구 결과에 「[앱 요령]」이 붙는다 — 그 앱의 화면 구조·하는 법·주의가 있으니 먼저 읽고 따른다.
            - 메시지 발송·송금·결제·구매·삭제·전화 걸기처럼 되돌릴 수 없는 동작은 반드시 irreversible=true 로 호출한다. 단 「그 탭 자체가 실행하는」 경우만이다 — 내용에 그런 단어가 있는 노트·채팅방·거래 줄을 여는 탭은 해당 없다.
            - 비밀번호·인증번호·결제 비밀번호 입력은 하지 말고, 그 지점에서 finish 로 사용자에게 넘긴다.
            - 인증번호·누가 연락했나 = notifications 먼저(알림창을 열지 않는다) · 문자 내용 = sms_read(메시지 앱을 열지 않는다) · 받은 문서를 폰에 두기 = save_to_downloads.
            - 화면 목록(접근성 트리)은 글자·버튼만 담는다. 사진·영상·그림·지도·차트·게임처럼 트리에 안 나오는 내용은 look 으로 눈으로 본다(스크린샷을 비전 모델이 읽어 준다). 트리로 충분하면 쓰지 않는다(느리다).
            - 이전 화면은 다음 단계에 지워진다. 여러 화면에서 정보를 모으는 일(목록 → 상세 여러 개)은 읽는 즉시 note 로 적어 두고, 이미 적은 항목은 다시 열지 않는다. 메모는 매 단계 [메모]로 다시 보여 준다.
            - 도구 결과에 「⚠️ 화면 변화 없음」이 오면 같은 동작을 반복하지 말고 다른 방법(다른 항목, 뒤로, 스크롤, 기다리기)을 쓴다.
            - 「⟲ 이미 본 화면」·[지금까지 본 화면]은 같은 곳을 다시 보고 있다는 뜻이다 — 안 본 곳([↓더 있음])이나 다른 경로로 간다. 「[재계획]」 메시지가 오면 그 전략을 따르되, 지금 화면과 다르면 화면을 따른다.
            - 「과거 성공 경로」가 주어지면 참고하되, 지금 화면과 다르면 화면을 따른다.
            - 오류·경고 문구(예: 「제공하지 않는 지역」, 「실패」, 「오류코드」)가 뜨면 그 내용을 사용자에게 그대로 전한다. 오류창을 닫은 것은 성공이 아니다.
            - 요청에 담긴 조건을 하나도 흘리지 않는다: 출발지·도착지·경유지·이동수단·경로 옵션(최단 시간 등)·대상·시간·수량. 사용자가 출발지를 말했으면 현재 위치로 대신하지 않는다.
            - finish 전에 요청한 상태가 지금 화면에 실제로 있는지 확인한다. finish 의 conditions 에 요청 조건을 전부 적고, 화면으로 확인된 것만 met=true. 하나라도 못 이뤘으면 success=false 로 무엇이 막았는지 말한다.
            - 이전 요청이 멈췄거나(취소·실패) 끝나지 않아 결과가 없는데 이번 요청이 그 결과를 쓰면(예: 「그것들을 메모장에 정리해 줘」), 사용자에게 다시 하라고 넘기지 말고 이전 요청부터 이어서 끝까지 한다.
            - 스크린샷·캡처는 capture 로 한다(전체 또는 from/to 항목 영역만). 「상품마다」「각각」이면 항목마다 그 항목 화면에서 따로 찍는다 — 목록 한 장으로 대신하지 않는다.
            - 노트·메모 앱(삼성 노트)에 정리할 때는 write_note 한 번으로 한다: 제목을 채우고, 이번 작업에서 캡처한 이미지가 있으면 항목마다 글 다음에 그 이미지 블록을 둔다(사용자가 따로 말하지 않아도). 기존 노트에 덧붙일 땐 mode=append 로 그 노트 제목을 준다. 노트 화면을 직접 조작하지 않는다.
            - 캡처한 사진을 카톡 등으로 보낼 땐 갤러리에서 고르지 말고 share_images 로 공유 화면을 연다.
            - 「캡처」「스크린샷」 요청에서 무엇을 담으라는지(예: 장소 정보 화면, 상품 상세)가 있으면 그 정보가 다 들어가게 찍는다 — 이름 한 줄만 자르지 않는다. 애매하면 전체 화면.
            - 요청이 애매하면 되묻기 전에 가장 그럴듯한 해석으로 한다(예: 「그 상품 해당 부분만 잘라」 = 방금 다룬 첫 상품의 상품명~가격). finish 에서 어떻게 해석했는지 말해 사용자가 바로잡을 수 있게 한다. 되묻는 것은 되돌릴 수 없는 동작이거나 해석에 따라 결과가 크게 갈릴 때만.
            - 이 대화에는 이전 요청·도구 내역·답변이 이어져 있다. 「그거」「아까」「방금」 같은 말은 이전 대화에서 찾는다. 화면 조작 없이 답할 수 있으면 도구 없이 바로 finish 한다.
            - 끝나면 finish 로 결과를 한국어 한두 문장으로 말한다(음성으로 읽힌다). 질문형 요청이면 화면에서 읽은 답을 말한다.
        """.trimIndent()

        // open_screen 허용 목록 — 전부 이 폰에서 resolve-activity 로 확인한 것(2026-09-26). LLM 이 지어낸 액션은 넣지 않는다
        // QR: 핸드오프가 추정한 com.samsung.android.intent.action.QR_SCANNER 는 없고, 카메라의 QR_SCANNER_MODE 는 해석은 되지만
        // 다른 앱에서 열리지 않는다(unable to resolve) → 실제로 열리는 SCAN_QR_CODE
        private val SCREENS = linkedMapOf(
            "new_event" to "android.intent.action.INSERT",
            "alarms" to "android.intent.action.SHOW_ALARMS",
            "timers" to "android.intent.action.SHOW_TIMERS",
            // 스톱워치·세계시각(INTENT_STOPWATCH/WORLDCLOCK)은 뺐다 — 필터에 DEFAULT 가 없어 암시적으로 안 잡히고, 컴포넌트를 박으면
            // Permission Denial(비공개). 셸 resolve-activity 는 둘 다 잡아 「있다」로 속았다 → 검증은 앱에서 실제로 열어 본다
            "photo_camera" to "android.media.action.STILL_IMAGE_CAMERA",
            "video_camera" to "android.media.action.VIDEO_CAMERA",
            "qr_scan" to "com.sec.android.app.camera.action.SCAN_QR_CODE",
        )

        // quick_toggle 허용 타일(설명 글자에 들어 있는 이름). 와이파이·비행기 모드·모바일 데이터는 넣지 않는다 — 끄면 모델 연결이 끊겨
        // 이 비서가 그 자리에서 멈춘다(사용자 결정 2026-09-26). 핫스팟·스마트 뷰·Windows와 연결은 밖으로 내보내는 것이라 뺀다
        private val TILES = linkedMapOf(
            "bluetooth" to "블루투스",
            "auto_rotate" to "자동 회전",
            "nfc" to "NFC",
            "power_saving" to "절전 모드",
            "dark_mode" to "다크 모드",
        )

        private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
            JSONObject().put("type", "function").put(
                "function", JSONObject().put("name", name).put("description", desc).put(
                    "parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))
                )
            )

        private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

        val TOOLS: JSONArray = JSONArray()
            .put(fn("open_app", "설치된 앱을 연다", JSONObject().put("name", p("string", "앱 이름(「설치된 앱」 표기) 또는 패키지명")), listOf("name")))
            .put(fn("list_apps", "설치된 앱 이름과 패키지를 본다", JSONObject().put("query", p("string", "이름 일부(비우면 전체)"))))
            .put(fn("tap", "화면 항목을 탭한다", JSONObject()
                .put("index", p("integer", "화면 항목 번호"))
                .put("irreversible", p("boolean", "발송·결제·삭제 등 되돌릴 수 없는 동작이면 true")), listOf("index")))
            .put(fn("long_press", "화면 항목을 길게 누른다", JSONObject().put("index", p("integer", "화면 항목 번호")), listOf("index")))
            .put(fn("type_text", "입력칸에 글자를 넣는다(기존 내용은 바뀜). index 를 생략하면 입력칸을 누르지 않고 지금 커서 자리에 이어 쓴다 — 노트에서 이미지 넣은 뒤 그 아래에 이어 쓸 때(누르면 커서가 누른 자리로 옮겨진다)", JSONObject()
                .put("index", p("integer", "입력칸 번호(생략 = 지금 커서 자리에 이어 쓰기)"))
                .put("text", p("string", "넣을 글자"))
                .put("submit", p("boolean", "입력 후 키보드의 완료/검색/보내기 키를 누를지"))
                .put("irreversible", p("boolean", "submit 이 메시지 발송 등 되돌릴 수 없는 동작이면 true")), listOf("text")))
            .put(fn("scroll", "화면을 스크롤한다", JSONObject()
                .put("direction", JSONObject().put("type", "string").put("enum", JSONArray(listOf("down", "up", "left", "right"))))
                .put("index", p("integer", "스크롤할 항목 번호(생략하면 화면에서 가장 큰 스크롤 영역)")), listOf("direction")))
            .put(fn("global", "시스템 버튼. screenshot = 지금 화면을 캡처해 갤러리 「스크린샷」에 저장(사용자가 찍은 것과 같음 · 요청한 화면이 실제로 떠 있는지 [현재 화면]으로 확인한 뒤 찍는다) · lock_screen = 화면 끄고 잠금(이후 조작 불가)", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("back", "home", "recents", "notifications", "screenshot", "lock_screen")))), listOf("action")))
            .put(fn("media", "재생 중인 음악·영상을 제어한다(앱을 열지 않고)", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("play", "pause", "play_pause", "next", "previous", "stop")))), listOf("action")))
            .put(fn("volume", "음량을 바꾼다. 결과에 바뀐 값이 온다", JSONObject()
                .put("stream", JSONObject().put("type", "string").put("enum", JSONArray(listOf("media", "ring", "notification", "alarm", "call")))
                    .put("description", "생략하면 media(음악·영상)"))
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("up", "down", "set", "mute", "unmute", "get"))))
                .put("percent", p("integer", "action=set 일 때 0-100")), listOf("action")))
            .put(fn("flashlight", "손전등(플래시)을 켜거나 끈다", JSONObject()
                .put("on", p("boolean", "켜기 true · 끄기 false")), listOf("on")))
            .put(fn("brightness", "화면 밝기를 바꾼다. 결과에 바뀐 값이 온다", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("set", "up", "down", "auto", "get"))))
                .put("percent", p("integer", "action=set 일 때 0-100")), listOf("action")))
            .put(fn("sound_mode", "소리 모드(소리/진동/무음)와 방해금지를 바꾸거나 본다", JSONObject()
                .put("mode", JSONObject().put("type", "string").put("enum", JSONArray(listOf("sound", "vibrate", "silent", "dnd_on", "dnd_off", "get")))), listOf("mode")))
            .put(fn("open_screen", "시스템 앱의 특정 화면을 바로 연다(폰에서 검증된 것만). new_event = 캘린더 새 일정 작성 화면(저장은 사용자 확인 후) · alarms/timers = 시계 앱 알람·타이머 목록 · photo_camera/video_camera = 카메라 촬영 모드 · qr_scan = QR 스캐너", JSONObject()
                .put("name", JSONObject().put("type", "string").put("enum", JSONArray(SCREENS.keys.toList())))
                .put("title", p("string", "new_event: 일정 제목"))
                .put("begin", p("string", "new_event: 시작 yyyy-MM-dd HH:mm"))
                .put("end", p("string", "new_event: 끝 yyyy-MM-dd HH:mm(생략하면 1시간)"))
                .put("all_day", p("boolean", "new_event: 종일")), listOf("name")))
            .put(fn("clipboard", "클립보드를 읽거나(get — 사용자가 복사해 둔 글) 글을 넣는다(set — 다른 앱에 붙여넣을 수 있게). get 은 지금 내용을 실제로 읽는다(대화 기억으로 답하지 말 것)", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("get", "set"))))
                .put("text", p("string", "set 일 때 넣을 글")), listOf("action")))
            .put(fn("write_note", "삼성 노트에 쓴다(코드가 조작·확인). mode=new(기본): 새 노트를 만들어 제목과 글·이미지 블록을 순서대로 · mode=append: 제목이 정확히 같은 기존 노트를 찾아 본문 끝에 블록을 이어 쓴다(「방금 노트에 ○○ 추가」「그 노트에 캡처 하나 더」). 노트를 쓰거나 고칠 땐 화면을 직접 조작하지 말고 이것 한 번으로. 이미지는 capture 로 저장한 파일 이름", JSONObject()
                .put("mode", JSONObject().put("type", "string").put("enum", JSONArray(listOf("new", "append"))).put("description", "new = 새 노트(기본) · append = 기존 노트 끝에 이어쓰기"))
                .put("title", p("string", "new: 노트 제목(비우지 말 것) · append: 이어 쓸 노트의 제목(목록에 보이는 그대로)"))
                .put("blocks", JSONObject().put("type", "array").put("description", "위에서부터 순서대로. 이미지는 그 이미지가 보여 주는 항목의 글 바로 다음 블록에 둔다 — {text: 상품1 글}, {image: 상품1 캡처}, {text: 상품2 글}, {image: 상품2 캡처} … (끝에 몰아 넣지 않는다)")
                    .put("items", JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("text", p("string", "글(여러 줄 가능)")).put("image", p("string", "capture 결과의 파일 이름. 예: FoldAgent_20260926_172708_상품1.jpg")))))
                , listOf("title", "blocks")))
            .put(fn("trash_captures", "capture 로 저장한 사진(FoldAgent_…, 이 앱이 만든 것만)을 휴지통으로 옮긴다(30일 안에 복구 가능). 사용자 확인을 거친다. 갤러리에서 사진을 골라 지우지 말 것(썸네일이 「버튼」뿐이라 어느 사진인지 모른다)", JSONObject()
                .put("files", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                    .put("description", "사진 파일 이름 또는 그 일부. 모르면 all=true"))
                .put("all", p("boolean", "이 앱이 만든 캡처 전부"))))
            .put(fn("share_images", "capture 로 저장한 사진을 다른 앱(카톡 등)의 공유 화면으로 바로 넘긴다 — 갤러리에서 사진을 고르지 말 것(썸네일이 「버튼」뿐이라 어느 사진인지 확인이 안 된다). 공유 대상(방·사람) 고르기부터는 화면에서 하고, 전송은 확인이 필요하다", JSONObject()
                .put("files", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                    .put("description", "사진 파일 이름 또는 그 일부(capture 결과 이름. 예: FoldAgent_20260926_191100_샥즈.jpg 또는 「서울숲」). 모르면 아무 말이나 넣으면 최근 사진 목록을 돌려준다"))
                .put("package", p("string", "받을 앱 패키지. 카카오톡 = com.kakao.talk · 비우면 시스템 공유 창")), listOf("files")))
            .put(fn("capture", "지금 화면을 찍어 갤러리 「스크린샷」에 저장한다. from/to 항목 번호를 주면 그 두 항목을 감싸는 영역만 잘라 저장(예: 상품명~가격 부분만). 둘 다 생략하면 전체 화면. 화면에 보이는 부분만 찍힌다 — 찍기 전에 담을 것의 처음(예: 상품명)과 끝(예: 가격)이 둘 다 [현재 화면]에 있는지 보고, 없으면 scroll 로 맞춘 뒤 찍는다. 부분 캡처는 include 에 담을 것의 처음·끝 글을 넣는다. 전체 화면이면 from/to/include 모두 생략", JSONObject()
                .put("from_index", p("integer", "잘라낼 영역의 첫 항목 번호(위쪽)"))
                .put("to_index", p("integer", "잘라낼 영역의 끝 항목 번호(아래쪽 · 생략하면 from 항목 하나)"))
                .put("name", p("string", "파일 이름에 붙일 짧은 말(선택). 예: 상품1"))
                .put("include", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                    .put("description", "부분 캡처일 때만: 꼭 들어가야 할 글(처음·끝). 「상품명부터 가격까지」면 [상품명 앞부분, 가격]. 도구가 영역 안에 있는지 확인하고, 화면 밖이면 스크롤해 맞추거나 거부한다"))))
            .put(fn("quick_toggle", "퀵 설정 타일을 켜거나 끈다(현재 상태를 읽고 필요할 때만 누른 뒤 바뀌었는지 확인)", JSONObject()
                .put("tile", JSONObject().put("type", "string").put("enum", JSONArray(TILES.keys.toList())))
                .put("on", p("boolean", "켜기 true · 끄기 false")), listOf("tile", "on")))
            .put(fn("system_setting", "허용된 시스템 설정값을 화면을 거치지 않고 읽거나 바꾼다. 설정 화면에 항목이 안 보여도 값은 있다 — 화면을 뒤지기 전에 get 으로 확인한다. key 를 생략한 get = 허용 목록 전체 값. set 은 사용자 확인을 거친다(바꿀 수 있는 것: stay_on_while_plugged_in = 충전 중 화면 계속 켜짐 · screen_off_timeout = 화면 자동 꺼짐 시간 · accelerometer_rotation = 자동 회전 — 자동 회전은 quick_toggle(auto_rotate) 가 먼저, 타일이 없거나 실패했을 때만). 와이파이·비행기·모바일 데이터는 읽기만", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("get", "set"))))
                .put("key", JSONObject().put("type", "string").put("enum", JSONArray(SysSettings.KEYS.keys.toList()))
                    .put("description", "get 에서 생략하면 전체"))
                .put("value", p("string", "set 일 때 사람 말 그대로: on/off · 켜기/끄기 · 화면 꺼짐 시간은 「5분」「30초」「최대」")), listOf("action")))
            .put(fn("stopwatch", "스톱워치를 시작(멈춘 것은 이어서)·정지·초기화·구간 기록하거나 경과 시간을 본다(시계 앱을 알아서 조작)", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("start", "stop", "reset", "lap", "get")))), listOf("action")))
            .put(fn("swipe", "손가락으로 밀기(스크롤로 안 되는 것: 알림·목록 항목 밀어서 지우기/메뉴, 카드 넘기기, 지도 이동, 화면 가장자리에서 끌어오기). direction 은 손가락이 움직이는 방향", JSONObject()
                .put("direction", JSONObject().put("type", "string").put("enum", JSONArray(listOf("left", "right", "up", "down"))))
                .put("index", p("integer", "이 항목 위에서 민다(생략하면 화면 가운데)"))
                .put("from_edge", p("boolean", "화면 가장자리에서 시작(엣지 패널·뒤로 제스처 등)")), listOf("direction")))
            .put(fn("set_alarm", "알람을 맞춘다", JSONObject()
                .put("hour", p("integer", "0-23")).put("minute", p("integer", "0-59")).put("label", p("string", "알람 이름(선택)")), listOf("hour", "minute")))
            .put(fn("open_link", "딥링크/URL 로 앱의 특정 화면을 바로 연다(검색 결과·길찾기·전화 걸기 화면 등)", JSONObject()
                .put("uri", p("string", "예: https://m.youtube.com/results?search_query=… · tel:0101234 (다이얼만 열림)"))
                .put("package", p("string", "이 앱으로 열기(선택, 패키지명)")), listOf("uri")))
            .put(fn("geocode", "장소 이름·주소를 좌표로 바꾼다(길찾기 딥링크용). 후보 여러 개를 주소와 함께 돌려준다", JSONObject()
                .put("query", p("string", "장소 이름 또는 주소. 예: 서울시청, 서울 중구 세종대로 110")), listOf("query")))
            .put(fn("open_settings", "설정 앱의 특정 화면을 바로 연다", JSONObject()
                .put("page", p("string", "설정 액션. 예: WIFI_SETTINGS, BLUETOOTH_SETTINGS, DISPLAY_SETTINGS (android.settings. 생략 가능)"))
                .put("package", p("string", "특정 앱의 화면일 때 그 앱(이름 또는 패키지). 예: page=APPLICATION_DETAILS_SETTINGS(앱 정보) · APP_NOTIFICATION_SETTINGS(그 앱 알림)")), listOf("page")))
            .put(fn("set_timer", "타이머를 맞춘다", JSONObject()
                .put("seconds", p("integer", "초")).put("label", p("string", "타이머 이름(선택)")), listOf("seconds")))
            .put(fn("look", "지금 화면을 스크린샷으로 보고 질문에 답을 받는다(사진·영상·그림 내용 파악용). 보안 화면은 검게 나온다. files 를 주면 화면 대신 [받은 파일] 의 이미지들을 본다", JSONObject()
                .put("question", p("string", "화면에서 알고 싶은 것. 예: 이 스토리 사진에 무엇이 있나, 글자는 뭐라고 쓰여 있나"))
                .put("files", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                    .put("description", "[받은 파일] 의 이미지 파일 id(최대 5, 선택) — 주면 화면이 아니라 이 이미지들을 본다")), listOf("question")))
            .put(fn("note", "모은 정보를 메모한다(화면이 지워져도 남는다). 예: 거래1: 3/12 스타벅스 -5,800원", JSONObject()
                .put("text", p("string", "메모할 내용 한 줄")), listOf("text")))
            .put(fn("notifications", "폰에 온 알림을 화면을 거치지 않고 읽는다(알림창을 열지 않는다). 인증번호·「누가 연락했어」·「카톡 뭐 왔어」에 먼저 쓴다. 지금 알림창에 남은 것 + 이 앱이 켜진 뒤 받은 것(지운 알림 포함)이 보인다. 결과: 시각 · 앱 · 제목 · 본문 줄(새것부터)", JSONObject()
                .put("app", p("string", "앱 이름 또는 패키지 일부(선택). 예: 카카오톡, 메시지, 토스"))
                .put("since_min", p("integer", "최근 몇 분(기본 60)"))
                .put("limit", p("integer", "최대 건수(기본 10, 최대 30)"))
                .put("query", p("string", "제목·본문에 들어 있는 글(선택). 예: 인증번호"))))
            .put(fn("sms_read", "받은·보낸 문자(SMS)를 화면을 거치지 않고 읽는다(읽기 전용 — 보내지 않는다). 최신 N건을 오래된 순으로 준다", JSONObject()
                .put("with", p("string", "상대 번호(일부 가능 — 뒤 8자리로 맞춘다) 또는 주소 칸에 보이는 이름(선택). 연락처 이름으로는 못 찾는다"))
                .put("query", p("string", "본문에 들어 있는 글(선택). 예: 인증번호, 택배"))
                .put("limit", p("integer", "최대 건수(기본 10, 최대 30)"))
                .put("since_days", p("integer", "최근 며칠(선택, 생략하면 기간 제한 없음)"))))
            .put(fn("save_to_downloads", "이 대화에서 받은 첨부 파일(홈맥이 만든 문서·그림 등 채팅에 붙은 것)을 폰의 공용 「다운로드」 폴더에 저장한다(내 파일 앱·다른 앱에서 열 수 있게). 경로를 지어내지 말 것 — 첨부 이름으로만 고른다", JSONObject()
                .put("file", p("string", "첨부 이름(채팅·[첨부]에 보이는 그대로, 일부 가능). 예: 보고서.hwpx"))
                .put("name", p("string", "다운로드에 둘 이름(선택 — 생략하면 첨부 이름. 같은 이름이 있으면 (2)가 붙는다)")), listOf("file")))
            .put(fn("wait", "화면이 로딩될 때까지 기다린다", JSONObject().put("seconds", p("number", "0.5-5"))))
            .put(fn("choose_way", "처리할 길이 실질적으로 다른 2~3개일 때 첫 단계에서 한 번, 사용자에게 방법을 고르게 한다(선택 카드 · 시간 안에 안 고르면 추천안). 결과로 고른 방법이 온다", JSONObject()
                .put("question", p("string", "한 줄 질문(60자 이하). 예: 양실장 영상 어디서 찾을까요?"))
                .put("options", JSONObject().put("type", "array").put("description", "선택지 2~3개")
                    .put("items", JSONObject().put("type", "object")
                        .put("properties", JSONObject()
                            .put("label", p("string", "방법 이름(24자 이하). 예: 유튜브 앱에서 직접"))
                            .put("detail", p("string", "걸리는 시간·폰 화면을 쓰는지·비용 한 줄(60자 이하). 예: 1~2분 · 화면 씀 · 무료")))
                        .put("required", JSONArray(listOf("label", "detail")))))
                .put("recommended", p("integer", "추천 선택지 번호(0부터) — 안 고르면 이걸로 진행")), listOf("question", "options", "recommended")))
            .put(fn("finish", "작업을 끝내고 사용자에게 말한다", JSONObject()
                .put("message", p("string", "한국어 한두 문장"))
                .put("success", p("boolean", "요청한 상태를 실제로 이뤘으면 true. 오류·막힘·일부만 했으면 false"))
                .put("conditions", JSONObject().put("type", "array").put("description", "요청에 담긴 조건 전부(예: 출발지=집, 도착지=회사, 수단=자동차, 옵션=최단 시간)")
                    .put("items", JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("condition", p("string", "조건")).put("met", p("boolean", "화면으로 확인됐으면 true")))
                        .put("required", JSONArray(listOf("condition", "met"))))), listOf("message", "success", "conditions")))
            .also { arr -> AssistantTools.TOOLS.forEach { arr.put(it) } }   // 홈 비서 도구(계약 §1)
            .also { arr -> OaiTools.TOOLS.forEach { arr.put(it) }; CapTools.TOOLS.forEach { arr.put(it) } }   // 웹·그림 · 스킬·실행·MCP(계약 cc-capabilities §1)
    }

    @Volatile private var cancelled = false
    private val trace = TraceLog(svc)
    /** 맡긴 일 뒤 자동 이어가기 — 화면 도구가 필요해지면 멈춘다(pausedFor = 그 도구) */
    private val noScreen = meta.optBoolean("noScreen", false)
    @Volatile private var pausedFor: String? = null
    /** 남은 단계를 then 으로(또는 claude_task 로 통째로) 맡겼다 — 이번 턴의 못 맞춘 조건은 이어가기가 채우니 실패로 치지 않는다 */
    private var handedOffThen = false
    val runId get() = trace.runId
    private val kb get() = svc.knowledge
    private var snap: Snapshot? = null
    private val messages = ArrayList<JSONObject>()
    private val steps = JSONArray()                 // 레시피용 요약 경로
    private val failedAppNames = ArrayList<String>() // open_app 실패 이름 — 결국 연 앱과 이어 별칭으로 배운다
    private val notedPkgs = HashSet<String>()
    private val memo = ArrayList<String>()             // note 도구 — 요청마다 [메모]로 다시 붙인다
    private val actionHistory = ArrayList<String>()    // 오가는 반복(A→B→A→B) 감시용
    private var oscillating = false
    private var failStreak = 0                          // 도구 실패 연속 — 이상 징후
    private var history: List<JSONObject> = emptyList() // 세션의 이전 턴들(요청 앞에 붙는다)      // 이번 실행에서 이미 요령을 보여 준 앱
    private var zeroStepChallenged = false              // 도구 없이 성공 종료를 한 번 되물었나(2026-09-26 QA-D2·B3 거짓 성공)
    private var noteWriteFailed = false                 // write_note 가 중단을 보고했는데 뒤에 성공한 쓰기가 없으면 finish 가 success 여도 실패로
    private var repeatKey = ""
    // 방법 고르기(choose_way) — 한 실행 한 번. wayStopped = 카드에서 「그만」 → 이후 도구는 막고 finish 만
    private var chosenWay: String? = null
    private var wayStopped = false
    /** 첫 도구가 웹 검색·스킬 찾기·위임이면 한 번 멈춰 choose_way 를 먼저 생각하게 했나 — 프롬프트 규칙만으론 모델이 늘 skill_search 부터 했다(실기 2026-09-28 01:38·01:48) */
    private var wayNudged = false
    /** 이번 실행에 화면 도구를 한 번이라도 썼나 · 화면 없는 도구만 쓰고 실패로 끝내려 할 때 한 번 되돌렸나 */
    private var usedScreen = false
    private var fallbackChallenged = false
    /** 사람 개입에서 10분 동안 「이어서」가 없어 멈췄다 */
    private var hitlTimedOut = false
    /** 이번 실행에 choose_way 를 보일지 — 설정 꺼짐·자동 이어가기(화면 없이 도는 실행)면 뺀다 */
    private val wayAllowed by lazy { Prefs.askWay(svc) && !noScreen }
    private val tools: JSONArray by lazy {
        if (wayAllowed) TOOLS else JSONArray().also { arr ->
            for (k in 0 until TOOLS.length()) TOOLS.getJSONObject(k).let { if (it.getJSONObject("function").getString("name") != "choose_way") arr.put(it) }
        }
    }
    private val declinedSettings = HashSet<String>()     // 이번 run 에서 게이트가 거부(무응답 포함)된 system_setting 키 — 다시 묻지 않는다
    private var declinedRetries = 0                      // 거부된 키로 set 을 또 부른 횟수 — 두 번째면 멈춘다
    private var repeatCount = 0
    // 홈 비서 — 진행 중인 원격 호출(정지하면 abort 로 연결을 끊는다). 실행마다 Prefs 를 새로 읽게 run 안에서 만든다
    @Volatile private var assistantClient: AssistantClient? = null
    private val seenEvents = HashMap<String, JSONObject>()   // 이번 실행에서 agenda 로 본 일정(id → 일정) — event_delete 확인 창에 제목·시각을 넣는다
    // 거부 키(도구명:대상) → 이번 실행에서 그 대상에 쓴 confirmId. 같은 대상을 다시 승인해도 같은 confirmId 로 보내
    // 서버 멱등 캐시가 두 번째 실행을 막는다 — 시간초과·끊김 뒤 모델이 다시 부르면 새 UUID 로 이중 발송될 수 있었다(리뷰 safety#3)
    private val assistantConfirmIds = HashMap<String, String>()
    private val assistantCalls = ArrayList<String>()         // 조회형 비서 호출(도구+정규화 인자) — 같은 조회 반복 감시(리뷰 runtime#6)
    // 이번 실행에서 도구들이 만든 첨부(계약 cc-capabilities §1-3) — 모델이 고르지 않는다(지어낼 수 없다). 턴 저장 때 넘긴다
    private val attachments = ArrayList<JSONObject>()
    // 본 화면 기억·막히면 재계획(2026-09-27) — 여기엔 연결만, 본체는 SeenScreens.kt·Replanner.kt
    private val seenScreens by lazy { SeenScreens(svc.packageName) { p -> appLabel(p) } }
    private val replanner by lazy { Replanner(svc, trace, seenScreens) }
    private var lastChanged: Boolean? = null            // 이번 도구가 화면을 다시 읽었나(안 읽었으면 null) — 재계획 트리거용
    private var lastVisit: SeenScreens.Visit? = null
    private var lastGuard: String? = null               // 이번 단계의 반복 감시 경고(왕복 n≥3 · 같은 동작 무효 n≥3)
    private var lastUnchangedWarn = false               // 「⚠️ 화면 변화 없음」을 붙였나
    // 재계획 메시지 — 이번 실행에서는 기억으로 남기되 세션에는 머리 줄만 저장한다. 본문은 화면 전문을 읽고 지은 자유 서술이라
    // 금융·메신저의 금액·이름이 들어갈 수 있는데 slim 이 자를 표지가 없어 다음 턴 프롬프트마다 실렸다(리뷰 2026-09-27)
    private val replanMsgs: MutableSet<JSONObject> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    /** 알림·문자 도구 결과 메시지 → 세션에 저장할 본문 없는 사본(PRIVATE_READ_TOOLS) */
    private val privateMsgs = java.util.IdentityHashMap<JSONObject, JSONObject>()
    private val homePkg: String? by lazy {
        svc.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }

    fun cancel() {
        cancelled = true
        assistantClient?.abort()   // 비서 원격 호출 중이면 연결을 끊어 즉시 끝낸다(계약 §0-8)
        trace.event("cancel")
        svc.status("⏹ 멈추는 중…")
    }

    /**
     * 도구가 첨부 하나를 보고한다(계약 cc-capabilities §1-3). 모양:
     *   `{kind:"image"|"file"|"link", title, uri?, url?, mime?, size?}`
     *   - image·file: uri 필수 — 폰 content uri(갤러리 FoldAgent_… 이미지) 또는 앱 전용 폴더 files/attach/ 안 파일 이름
     *   - link: url 필수(http·https)
     *   - title: 사람이 볼 이름(비면 uri·url 끝부분) · mime·size(바이트) 는 알면
     * 모양이 틀리면 버리고 false. 같은 uri·url 은 한 번만. 한 실행 [MAX_ATTACHMENTS] 개까지. 위 키 말고는 저장하지 않는다.
     * 어느 스레드에서 불러도 된다.
     */
    /** 이번 대화에서 받은 첨부(새것부터) — 이번 실행 것 + 저장된 턴들(디스크에서 새로 읽는다: 맡긴 일 결과가 실행 뒤에 붙는다). save_to_downloads 가 여기서만 고른다 */
    private fun knownAttachments(): List<kr.joonlab.foldagent.records.Attachment> {
        val out = ArrayList<kr.joonlab.foldagent.records.Attachment>()
        synchronized(attachments) { out += kr.joonlab.foldagent.records.Attachments.parse(JSONArray(attachments.map { JSONObject(it.toString()) })).asReversed() }
        val turns = (kr.joonlab.foldagent.records.Records.store as? kr.joonlab.foldagent.records.FileRecordStore)?.raw(session.id)?.optJSONArray("turns") ?: session.turns
        for (i in turns.length() - 1 downTo 0) out += kr.joonlab.foldagent.records.Attachments.parse(turns.optJSONObject(i)?.optJSONArray("attachments")).asReversed()
        return out
    }

    fun addAttachment(a: JSONObject): Boolean {
        val kind = a.optString("kind")
        if (kind !in setOf("image", "file", "link")) return false
        val uri = a.optString("uri").trim().takeIf { it.isNotEmpty() && it != "null" }
        val url = a.optString("url").trim().takeIf { it.isNotEmpty() && it != "null" }
        if (kind == "link" && (url == null || !(url.startsWith("https://") || url.startsWith("http://")))) return false
        if (kind != "link" && uri == null) return false
        val clean = JSONObject().put("kind", kind)
            .put("title", a.optString("title").trim().ifEmpty { (url ?: uri!!).trimEnd('/').substringAfterLast('/') }.take(200))
        uri?.let { clean.put("uri", it) }
        url?.let { clean.put("url", it) }
        a.optString("mime").trim().takeIf { it.isNotEmpty() && it != "null" }?.let { clean.put("mime", it) }
        if (a.has("size")) a.optLong("size", -1).takeIf { it >= 0 }?.let { clean.put("size", it) }
        synchronized(attachments) {
            if (attachments.size >= MAX_ATTACHMENTS) return false
            if (attachments.any { (uri != null && it.optString("uri") == uri) || (url != null && it.optString("url") == url) }) return false
            attachments += clean
        }
        trace.event("attach", JSONObject().put("kind", kind).put("title", clean.optString("title").take(60)))
        return true
    }

    /** @return 최종 상태 ok | failed(모델이 못 이뤘다고 보고) | cancel | limit | stuck | error */
    /** 앱 안(채팅 화면)에서 시켰고 아직 화면을 물리지 않았다 */
    private var inApp = false

    fun run(): String {
        val t0 = SystemClock.uptimeMillis()
        val llm = LlmClient(Prefs.base(svc), Prefs.model(svc), Prefs.apiKey(svc)) { msg ->
            svc.status("⏳ $msg"); trace.event("llm_retry", JSONObject().put("msg", msg))
        }
        svc.status("🎙 「$task」")
        svc.waitIdle()
        val seen = ScreenReader.read(svc)
        // 앱 안(채팅 화면)에서 시킨 일 — 앞 화면은 조작 대상이 아니다. 대화형 요청은 여기서 바로 답하고,
        // 화면 조작이 필요할 때만 execute 가 채팅 화면을 뒤로 물린다(2026-09-27: 무조건 홈으로 나갔다 돌아와 답을 읽던 것)
        inApp = seen.pkg == svc.packageName
        val first = if (!inApp) seen else Snapshot("앱: ${svc.packageName} · ${ScreenReader.foldState(svc)}\n" +
            "(지금 앞 화면은 사용자와 대화 중인 폴드 에이전트 채팅 앱이다 — 조작 대상이 아니다. 질문·설명·계산처럼 폰 화면이 필요 없는 요청은 도구 없이 바로 finish 로 답한다. " +
            "폰을 조작해야 하면 필요한 도구(open_app·open_settings·open_link·global 등)를 부르면 채팅 앱이 뒤로 물러나고 그 뒤 화면이 보인다)\n", emptyList(), svc.packageName)
        snap = first
        recordSeen(0, "(시작)", first, true)
        val recipes = kb.similar(task)
        history = session.historyMessages()
        trace.event("run_start", JSONObject(meta.toString())
            .put("session", session.id).put("turn", session.turns.length()).put("history_msgs", history.size)
            .put("request", task).put("model", Prefs.model(svc)).put("base", Prefs.base(svc))
            .put("build", BuildConfig.BUILD_TIME).put("knowledge", kb.version()).put("foreground", first.pkg)
            .put("recipes", JSONArray(recipes.map { it.optString("run") })))

        add(JSONObject().put("role", "system").put("content", systemPrompt()))
        val hint = if (recipes.isEmpty()) "" else "\n\n과거 성공 경로(참고):\n" + recipes.joinToString("\n") {
            "- 「${it.optString("request")}」: ${Knowledge.steps(it.optJSONArray("steps") ?: JSONArray())}"
        } + (if (wayAllowed && recipes.none { r -> (r.optJSONArray("steps")?.toString() ?: "").contains("choose_way") })
            // 같은 문장의 옛 경로(웹 검색만)를 그대로 따라 하느라 방법 고르기를 건너뛰었다(실기 2026-09-28 01:38)
            "\n(참고 경로는 지난번 한 가지 방법일 뿐이다 — 이번 요청도 길이 여럿이면 참고 경로보다 먼저 choose_way 로 고르게 한다)" else "")
        // 요청에 나온 앱의 요령도 처음부터 — 요령을 보려고 딥링크 앞에 open_app 을 먼저 부르던 것을 없앤다
        val mentioned = kb.mentionedPkgs(task).filter { it != first.pkg }
        if (mentioned.isNotEmpty()) trace.event("mentioned_apps", JSONObject().put("pkgs", JSONArray(mentioned)))
        // 폰에서 올린 파일(계약 INPUTS §1) — 요청 바로 뒤에 둔다: 턴 저장(Session.slim)이 [앱 요령]·[현재 화면] 부터 잘라 내므로
        // 그 앞에 있어야 다음 턴 맥락에도 파일 id 가 남는다(「그 파일 다시 …」)
        val uploads = Uploads.parse(meta)
        val upBlock = Uploads.block(uploads).let { if (it.isEmpty()) "" else "\n\n$it" }
        if (uploads.isNotEmpty()) trace.event("uploads", JSONObject().put("n", uploads.size)
            .put("fileIds", JSONArray(uploads.map { it.optString("fileId") })))
        add(JSONObject().put("role", "user").put("content", "요청: $task$upBlock$hint${appTips(first.pkg)}${mentioned.joinToString("") { appTips(it) }}\n\n$SCREEN_MARK\n${first.text}"))

        var final: String? = null
        var status = "ok"
        try {
            var step = 0
            var forcedReplan: String? = null
            while (true) {
                step++
                // 사람 개입 — 도구 사이에서만 선다(모델 대기 중이면 아래에서 그 답을 버리고 여기로 온다)
                if (svc.hitl != null && !cancelled) humanTurn(step)
                if (cancelled) { final = if (hitlTimedOut) "10분 동안 「이어서」가 없어 작업을 멈췄어요." else "작업을 멈췄습니다."; status = "cancel"; break }
                if (step > HARD_CAP) { final = "비상 상한(${HARD_CAP}단계)에 닿아 멈췄습니다 — 감시가 못 잡은 폭주일 수 있습니다."; status = "limit"; break }
                // 이번 단계 값만 트리거에 쓰이게 — execute 를 안 거친 단계(finish 되묻기 등)에서 지난 단계 guard 로 재계획이 걸렸다(리뷰)
                lastChanged = null; lastVisit = null; lastGuard = null; lastUnchangedWarn = false
                svc.status("🤔 생각 중… ($step)")
                val ts = SystemClock.uptimeMillis()
                svc.agentThinking = true
                val resp = try { llm.chat(requestMessages(), tools) } finally { svc.agentThinking = false }
                // 기다리는 사이 사용자가 넘겨받았다 — 이 답은 멈추기 전 화면 기준이라 버리고, 이어서 때 새 화면으로 다시 묻는다(사용자 결정)
                if (svc.hitl != null) {
                    trace.event("hitl_discard", JSONObject().put("step", step).put("ms", SystemClock.uptimeMillis() - ts))
                    step--; continue
                }
                val reply = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                val calls = reply.optJSONArray("tool_calls")
                val content = reply.opt("content").takeIf { it is String } as String?
                val ms = SystemClock.uptimeMillis() - ts
                trace.event("llm", JSONObject().put("step", step).put("ms", ms).put("usage", resp.optJSONObject("usage") ?: JSONObject.NULL))
                Log.i(TAG, "step $step LLM ${ms}ms calls=${calls?.length() ?: 0}")

                val am = JSONObject().put("role", "assistant").put("content", content ?: JSONObject.NULL)
                if (calls != null && calls.length() > 0) am.put("tool_calls", calls)
                add(am)
                if (calls == null || calls.length() == 0) {
                    if (needsZeroStepChallenge()) {
                        add(JSONObject().put("role", "user").put("content", ZERO_STEP_CHALLENGE)); continue
                    }
                    final = content?.takeIf { it.isNotBlank() } ?: "완료했습니다."
                    // finish 없이 글로 끝내도 같은 판정 — 「첨부 오류로 저장 안 됐다」고 말하며 ok 로 끝났다(2026-09-26 QA17)
                    if (noteWriteFailed) status = "failed"
                    break
                }

                var done = false
                for (k in 0 until calls.length()) {
                    val c = calls.getJSONObject(k)
                    val f = c.getJSONObject("function")
                    val name = f.getString("name")
                    val args = runCatching { JSONObject(f.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                    val result = when {
                        k > 0 -> "실행 안 함: 한 번에 도구 하나만 호출할 것"
                        cancelled -> "사용자가 작업을 멈춤"
                        name == "finish" && args.optBoolean("success", true) && needsZeroStepChallenge() -> ZERO_STEP_CHALLENGE
                        name == "finish" && !args.optBoolean("success", true) && needsFallbackChallenge() -> FALLBACK_CHALLENGE
                        name == "finish" -> {
                            final = args.optString("message", "완료했습니다.")
                            // 조건 체크리스트: 하나라도 met=false 면 모델이 success=true 라고 해도 실패로 기록한다
                            val conds = args.optJSONArray("conditions") ?: JSONArray()
                            val unmet = (0 until conds.length()).mapNotNull { conds.optJSONObject(it) }.filter { !it.optBoolean("met", false) }
                            trace.event("conditions", JSONObject().put("conditions", conds).put("unmet", unmet.size))
                            if ((!args.optBoolean("success", true) && !handedOffThen) || (unmet.isNotEmpty() && !handedOffThen)) status = "failed"
                            // 「일부만 완료했다」고 말하면서 success=true 로 끝낸 적이 있다(2026-09-26 QA13) — 도구가 중단을 보고했으면 실패로 기록
                            if (noteWriteFailed) status = "failed"
                            if (unmet.isNotEmpty() && args.optBoolean("success", true) && !handedOffThen) {
                                final += " (못 맞춘 조건: ${unmet.joinToString(", ") { it.optString("condition") }})"
                            }
                            done = true; "종료"
                        }
                        else -> {
                            Log.i(TAG, "step $step → $name $args")
                            val fs0 = failStreak
                            execute(step, name, args).also { r ->
                                replanner.observe(step, name, args, r, lastChanged, lastVisit, lastUnchangedWarn, failStreak > fs0)
                            }
                        }
                    }
                    val toolMsg = JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", result)
                    if (name in PRIVATE_READ_TOOLS) {
                        // 알림·문자 본문은 이번 실행의 모델에게만 — trace 엔 첫 줄, 세션엔 첫 줄 + 안내(다음 턴에 필요하면 다시 읽는다)
                        val cut = JSONObject(toolMsg.toString()).put("content", result.lineSequence().first().take(200) + " (본문은 기록하지 않음 — 다시 필요하면 $name 로 다시 읽을 것)")
                        privateMsgs[toolMsg] = cut
                        add(toolMsg, cut)
                    } else add(toolMsg)
                    if (pausedFor != null && !done) {
                        final = "여기까지 이어서 했어요. 다음 단계는 폰 화면이 필요해서 멈췄어요 — 알림의 「이어서 하기」를 누르면 계속해요."
                        status = "paused"; done = true
                    }
                    val stop = when {
                        done -> null
                        repeatCount >= STOP_REPEATS || oscillating -> "같은 동작이 계속 효과가 없어 멈췄습니다." to "중단 직전: 같은 동작이 계속 효과 없음(무효 반복·왕복)"
                        failStreak >= FAIL_STREAK_STOP -> "도구 실패가 ${failStreak}번 이어져 멈췄습니다." to "중단 직전: 도구 실패 ${failStreak}번 연속"
                        else -> null
                    }
                    // 재계획이 남아 있으면 멈추기 전에 한 번 물러나 다시 생각한다 — 다 쓴 뒤 다시 걸리면 그때 stuck(2026-09-27)
                    if (stop != null && replanner.canReplan) forcedReplan = stop.second
                    else if (stop != null) {
                        final = replanner.giveUpMessage.ifBlank { stop.first }; status = "stuck"; done = true
                    }
                }
                if (done) break
                val why = forcedReplan ?: replanner.trigger(step, lastGuard, failStreak)
                forcedReplan = null
                // 사용자가 넘겨받는 중이면 재계획하지 않는다 — 사람 개입 요약이 그 역할을 한다(루프 맨 위에서 멈춘다)
                if (why != null && !cancelled && svc.hitl == null) {
                    if (replanner.canReplan) replanNow(step, why)
                    else {
                        // 재계획을 다 쓴 뒤 세 번째 트리거 — 설계대로 stuck. 재계획이 give_up 으로 낸 안내가 있으면 그 말로(replan.md 횟수 상한)
                        trace.event("replan_exhausted", JSONObject().put("step", step).put("reason", why))
                        final = replanner.giveUpMessage.ifBlank { "다른 방법을 두 번 찾아봤지만 여전히 진척이 없어 멈췄습니다." }
                        status = "stuck"; break
                    }
                }
            }
            if (final == null) { final = "멈췄습니다."; status = "error" }
        } catch (e: Exception) {
            Log.e(TAG, "agent error", e)
            trace.event("error", JSONObject().put("message", e.toString()).put("stack", Log.getStackTraceString(e).take(4000)))
            final = "오류로 멈췄습니다. ${e.message?.take(80) ?: ""}"
            status = "error"
        }
        if (cancelled && status == "ok") status = "cancel"
        // 선택 카드에서 「그만」 — 실패가 아니라 사용자가 멈춘 것(성공 경로에도 안 남는다)
        if (wayStopped && status != "error") status = "cancel"
        val totalMs = SystemClock.uptimeMillis() - t0
        trace.event("run_end", JSONObject().put("status", status).put("message", final).put("steps", steps.length()).put("ms", totalMs))
        if (status == "ok" && steps.length() > 0) {
            kb.addRecipe(JSONObject().put("run", runId).put("request", task).put("steps", steps).put("ms", totalMs)
                .put("pkg", snap?.pkg).put("t", System.currentTimeMillis()))
        }
        // 대화 세션에 이번 턴을 남긴다(시스템 프롬프트 제외) — 다음 요청이 이어받는다.
        // 저장소가 디스크를 다시 읽고 붙이므로 실행 중 UI 에서 바꾼 제목·고정을 덮지 않는다. 출처·소요는 기록 목록용
        val saved = messages.drop(1).map { m ->
            if (m in privateMsgs) privateMsgs.getValue(m)
            else if (m !in replanMsgs) m
            else JSONObject(m.toString()).put("content", m.optString("content").substringBefore(']') + "] (재계획 내용은 그 실행에서만)")   // give_up 은 첫 줄에 안내가 붙으니 머리 괄호까지만
        }
        val atts = synchronized(attachments) { JSONArray(attachments.map { JSONObject(it.toString()) }) }
        session.addTurn(runId, task, final ?: "", status, steps, saved, meta.optString("source"), totalMs, atts, Uploads.turnRecord(uploads))
        svc.emit("finish", "$status\t$final")
        if (status == "paused") JobNotify.postContinue(svc, session.id, meta.optString("then").ifBlank { task }, final ?: "")
        // 화면 조작 없이 끝난 턴(대화형 답)은 채팅창을 다시 띄워 답을 보여 준다
        // system_setting get 도 조회라 비조작 — 빠져 있어 「화면 꺼짐 시간 몇 분이야?」 답이 음성으로만 나갔다(2026-09-27 검토)
        // 홈 비서 도구는 전부 뺀다 — 계약 §0-7 은 조회형(READ_ONLY)만 말하지만, 쓰기 도구(메일·일정·기억)도 폰 화면을 전혀 바꾸지 않고
        // 결과(「✅ 메일 보냄 — …」)가 채팅에만 남는다. 조작한 화면이 없으니 채팅을 다시 띄워 답을 보여 주는 게 맞다(화면 도구가 섞이면 그쪽이 acted 로 잡는다)
        val acted = (0 until steps.length()).any { steps.getJSONObject(it).let { st -> st.optString("tool") !in setOf("note", "geocode", "list_apps", "look", "clipboard", "choose_way", "save_to_downloads") + PRIVATE_READ_TOOLS + REMOTE_TOOLS &&
            !(st.optString("tool") == "system_setting" && st.optString("summary").startsWith("get ")) } }
        if (!acted && status != "cancel") svc.showChat()
        Log.i(TAG, "finish[$status]: $final")
        svc.status("${if (status == "ok") "✅" else "⚠️"} $final")
        svc.speak(final!!)
        return status
    }

    fun feedback(good: Boolean) {
        trace.event("feedback", JSONObject().put("good", good))
        if (!good) kb.markBad(runId)
    }

    /** 재계획 한 번 — 결과를 user 메시지로 넣고, 감시 카운터를 비워 옛 기록으로 곧바로 멈춤·재발동하지 않게 한다(설계 replan.md) */
    private fun replanNow(step: Int, reason: String) {
        val s = snap
        val msg = try {
            replanner.replan(step, reason, task, session.turns, memo.toList(), s?.pkg ?: "?", s?.text ?: "") { cancelled }
        } catch (e: Exception) {
            trace.event("error", JSONObject().put("where", "replan").put("message", e.toString()))
            "[재계획 — 오류로 기본 안내] ${Replanner.DEFAULT_ADVICE}"
        }
        add(JSONObject().put("role", "user").put("content", msg).also { replanMsgs += it })
        actionHistory.clear(); assistantCalls.clear(); repeatKey = ""; repeatCount = 0; oscillating = false; failStreak = 0
        replanner.resetAfter(step)
    }

    /**
     * 사람 개입(사용자 2026-09-28) — 에이전트는 여기서 도구도 모델도 부르지 않고 「▶ 이어서」·정지·10분 시한을 기다린다.
     * 이어서면 멈춘 뒤 사람이 누른 것·말한 것·지금 화면을 요약해 user 메시지로 넣고 **그 지점부터** 잇는다(처음부터 다시 하지 않게).
     * 감시 카운터는 비우고 재계획 횟수·단계 한도는 그대로(사용자 결정). 🔒 입력 글은 이번 실행 모델에게만 — trace·세션엔 「n자」.
     */
    private fun humanTurn(step: Int) {
        val h = svc.hitl ?: return
        val before = snap
        trace.event("hitl_pause", JSONObject().put("step", step).put("pkg", before?.pkg ?: "?"))
        svc.hitlAck(h)
        svc.emit("step", "✋ 사용자가 넘겨받음")
        var timedOut = false
        while (!cancelled && !h.resumed) {
            if (SystemClock.uptimeMillis() - h.ackAt > Hitl.MAX_MS) { timedOut = true; break }
            h.latch.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val sec = (SystemClock.uptimeMillis() - h.ackAt) / 1000
        if (cancelled || timedOut) {
            svc.hitlEnd(h)
            trace.event("hitl_end", JSONObject().put("step", step).put("how", if (timedOut) "timeout" else "cancel").put("sec", sec).put("events", h.eventCount()))
            if (timedOut) { hitlTimedOut = true; cancelled = true }
            return
        }
        svc.status("▶ 이어서 — 바뀐 화면 읽는 중…")
        Thread.sleep(300)   // 말하기 창이 닫히는 시간(resumeTask 가 닫는다)
        svc.waitIdle()
        val s = ScreenReader.read(svc)
        snap = s
        inApp = s.pkg == svc.packageName   // 사용자가 대화방을 열어 둔 채 이어서 → 다음 화면 도구가 채팅 화면을 물린다
        val label: (String) -> String = { appLabel(it) }
        val full = h.summary(task, before, before?.pkg?.let(label) ?: "?", label(s.pkg), label, typed = true)
        val cut = h.summary(task, before, before?.pkg?.let(label) ?: "?", label(s.pkg), label, typed = false)
        svc.hitlEnd(h, resume = true)
        val tips = appTips(s.pkg)
        val msg = JSONObject().put("role", "user").put("content", "$full$tips\n\n$SCREEN_MARK\n${s.text}")
        val saved = JSONObject(msg.toString()).put("content", "$cut$tips\n\n$SCREEN_MARK\n${s.text}")
        privateMsgs[msg] = saved   // 세션엔 입력 글 없는 사본
        add(msg, saved)
        // 사용자가 새 출발점을 줬다 — 반복·무진척 감시는 비운다. 재계획 횟수·단계 한도는 그대로
        actionHistory.clear(); assistantCalls.clear(); repeatKey = ""; repeatCount = 0; oscillating = false; failStreak = 0
        replanner.resetAfter(step)
        recordSeen(step, "(사람)", s, before == null || before.text != s.text)
        usedScreen = true
        steps.put(JSONObject().put("tool", "hitl").put("summary", h.shortLine(label)))
        trace.event("hitl_resume", JSONObject().put("step", step).put("sec", sec).put("events", h.eventCount()).put("said", h.saidCount())
            .put("pkg", s.pkg).put("summary", cut.take(1500)))
        svc.emit("step", "▶ 이어서: ${h.shortLine(label).take(70)}")
    }

    /** 본 화면 기억에 한 단계를 남긴다 — trace 에는 라벨 없이 서명·앱·재방문만(평가 세트가 결정론적으로 세게) */
    private fun recordSeen(step: Int, tool: String, s: Snapshot, changed: Boolean): SeenScreens.Visit? {
        val v = runCatching { seenScreens.record(step, tool, s, changed) }.getOrNull() ?: return null
        trace.event("seen", JSONObject().put("step", step).put("sig", v.sig).put("pkg", v.pkg).put("revisit", v.revisit).put("visits", v.visits))
        return v
    }

    // 런처에 없는 시스템 앱(배경화면·런처 자신 등)은 패키지 끝이 나왔다(「dressroom」, 사람 개입 실기 2026-09-28) — 시스템 앱 이름으로
    private fun appLabel(pkg: String): String =
        launcherApps().firstOrNull { it.first.activityInfo.packageName == pkg }?.second
            ?: runCatching { svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()?.takeIf { it.isNotBlank() && it != pkg }
            ?: pkg.substringAfterLast('.')

    private fun add(m: JSONObject, traceAs: JSONObject? = null) {
        messages += m
        trace.event("msg", traceAs ?: m)
    }

    private fun systemPrompt(): String {
        val apps = launcherApps().map { it.second }.distinct().sorted().joinToString(", ")
        val learned = kb.learnedSummary()
        return buildString {
            append(BASE_PROMPT)
            append("\n\n").append(AssistantTools.PROMPT)
            append("\n\n").append(OaiTools.PROMPT)
            append("\n\n").append(CapTools.PROMPT)
            append("\n\n## 지금\n").append(AssistantTools.nowLine())
            append("\n\n## 이 폰에 대해 아는 것\n").append(kb.notes().trim())
            if (learned.isNotEmpty()) append("\n\n## 폰이 스스로 배운 것\n").append(learned.trim())
            append("\n\n## 설치된 앱\n").append(apps)
        }
    }

    /** 시스템 + 이전 대화 + 이번 턴. 이번 턴은 최신 화면만 남기고 이전 화면은 잘라 토큰을 아낀다(기록에는 전문이 남아 있다). */
    private fun requestMessages(): JSONArray {
        val lastScreen = messages.indexOfLast { it.optString("content").contains(SCREEN_MARK) }
        val arr = JSONArray()
        messages.forEachIndexed { i, m ->
            if (i == 1) history.forEach { arr.put(it) }   // messages[0] = system, 그 뒤에 이전 대화
            val c = m.optString("content")
            if (i != lastScreen && c.contains(SCREEN_MARK)) {
                arr.put(JSONObject(m.toString()).put("content", c.substringBefore(SCREEN_MARK) + "(이전 화면 생략)"))
            } else if (i == lastScreen && (memo.isNotEmpty() || seenScreens.nonEmpty)) {
                // 본 화면 블록은 요청마다 새로 만들어 여기에만 붙인다 — messages 에 저장하지 않아 세션·다음 턴에 안 남는다
                val pre = buildString {
                    if (seenScreens.nonEmpty) append(seenScreens.block()).append("\n\n")
                    if (memo.isNotEmpty()) append("[메모 — 지금까지 모은 것]\n").append(memo.joinToString("\n")).append("\n\n")
                }
                arr.put(JSONObject(m.toString()).put("content", c.replace(SCREEN_MARK, pre + SCREEN_MARK)))
            } else arr.put(m)
        }
        return arr
    }

    private fun execute(step: Int, name: String, a: JSONObject): String {
        // 앱 안에서 시킨 일이 화면을 처음 필요로 하는 순간에만 채팅 화면을 물린다. 번호·좌표로 고르는 도구는 채팅 화면 기준 번호라 실행하지 않고
        // 물러난 뒤의 화면을 돌려줘 다시 고르게 한다(앞 화면 목록은 비어 있었다)
        // 화면을 쓰는 도구 직전마다: 채팅 화면이 앞에 있으면 물린다(처음 요청 때뿐 아니라 사용자가 작업 중 채팅으로 돌아온 경우도 — 리뷰 L2).
        // 번호·좌표로 고르는 도구는 채팅 화면 기준이었으니 실행하지 않고 새 화면을 돌려줘 다시 고르게 한다.
        lastChanged = null; lastVisit = null; lastGuard = null; lastUnchangedWarn = false
        // 선택 카드에서 「그만」 뒤엔 아무 도구도 실행하지 않는다 — finish 만(finish 는 execute 를 거치지 않는다)
        if (wayStopped) {
            failStreak++
            trace.event("tool", JSONObject().put("step", step).put("name", name).put("args", a).put("outcome", "막힘: 그만둠"))
            return "실행 안 함: 사용자가 그만뒀다 — 다른 도구를 부르지 말고 finish 로 끝낼 것"
        }
        if (name == "choose_way") return chooseWay(step, a)
        // 첨부가 있는 요청은 길이 사실상 정해져 있다 — 멈추면 「빠른 요약/정밀 분석」 같은 빈 선택지를 지어냈다(실기 2026-09-28 02:19)
        if (wayAllowed && chosenWay == null && !wayNudged && name in WAY_GATE_TOOLS && steps.length() == 0 && Uploads.parse(meta).isEmpty()) {
            wayNudged = true   // 한 실행 한 번 — 같은 도구를 다시 부르면 그대로 실행된다. 실패로 세지 않는다
            trace.event("way_nudge", JSONObject().put("step", step).put("tool", name))
            return "실행 안 함(한 번만 묻는다): 이 요청을 처리할 길이 여럿이면(폰 앱 화면에서 직접·웹 검색·홈맥 Claude Code 위임·스킬 실행 등) " +
                "먼저 choose_way 로 사용자에게 고르게 할 것. 사용자가 방법을 이미 정했거나 길이 사실상 하나면 방금 도구를 그대로 다시 부르면 실행된다"
        }
        // 홈 비서 도구 — 화면을 안 쓰므로 채팅 화면 물리기(yieldApp)·화면 다시 읽기를 전부 건너뛴다
        if (name in AssistantTools.NAMES) return assistantTool(step, name, a)
        // 웹·그림 · 스킬·실행·MCP — 마찬가지로 화면을 안 쓴다(계약 cc-capabilities §0-6)
        if (name in OaiTools.NAMES) return oaiTool(step, name, a)
        if (name in CapTools.NAMES) return capTool(step, name, a)
        // 맡긴 일 뒤 자동 이어가기(meta.noScreen)는 화면을 쓰지 않는다 — 화면이 필요해지면 여기서 멈추고 「이어서 하기」 알림(사용자 결정 2026-09-27)
        // look 에 files(받은 이미지)를 주면 화면을 안 쓴다 — 채팅 화면을 물리지 않고, 이어가기 실행에서도 된다
        val screenless = name in NO_SCREEN_TOOLS || (name == "look" && (a.optJSONArray("files")?.length() ?: 0) > 0)
        if (noScreen && !screenless) {
            pausedFor = name
            trace.event("paused_for_screen", JSONObject().put("step", step).put("tool", name))
            return "멈춤: 자동 이어가기는 폰 화면을 쓰지 않는다 — 이 단계부터는 사용자가 「이어서 하기」를 누르면 계속한다"
        }
        if (!screenless) {
            usedScreen = true
            val wasInApp = inApp
            inApp = false
            val y = svc.yieldApp()
            if (y == AgentService.Yield.FAILED) {
                trace.event("yield_failed", JSONObject().put("step", step).put("tool", name))
                return "실패: 폴드 에이전트 채팅 화면이 앞에 있어 조작하지 않았다 — 잠시 뒤 같은 도구를 다시 부르거나, 안 되면 finish 로 사용자에게 채팅 화면을 닫아 달라고 말할 것"
            }
            val picks = name in setOf("tap", "long_press", "type_text", "scroll", "capture") || (name == "swipe" && a.optInt("index", -1) >= 0)
            if (picks && (y == AgentService.Yield.YIELDED || wasInApp)) {
                if (y == AgentService.Yield.YIELDED) trace.event("yield_app", JSONObject().put("step", step).put("tool", name))
                snap = ScreenReader.read(svc)
                lastChanged = true; lastVisit = recordSeen(step, name, snap!!, true)
                return "폴드 에이전트 채팅 앱이 비켜났다 — 아래가 이제 조작할 화면이다. 번호를 이 화면에서 다시 골라 부를 것.\n\n$SCREEN_MARK\n${snap!!.text}"
            }
            if (y == AgentService.Yield.YIELDED) trace.event("yield_app", JSONObject().put("step", step).put("tool", name))
        }
        val before = snap
        val t0 = SystemClock.uptimeMillis()
        if (name == "look") {
            val q = a.optString("question").ifBlank { "이 화면에 무엇이 보이는지 설명해줘." }
            val fileIds = a.optJSONArray("files")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } } }?.distinct()?.take(5).orEmpty()
            svc.status(if (fileIds.isEmpty()) "👀 화면 보는 중…" else "👀 받은 이미지 보는 중…")
            val t0v = SystemClock.uptimeMillis()
            val r = try {
                if (fileIds.isNotEmpty()) {
                    // 받은 이미지(폰 사본) — 홈맥을 거치지 않는다
                    val missing = fileIds.filter { Uploads.localImage(svc, it) == null }
                    if (missing.isNotEmpty()) throw IllegalStateException("폰에 사본이 없는 파일 id: ${missing.joinToString()} — 이미지가 아니거나 오래됐다. 필요하면 code_run·claude_task 의 inputs 로")
                    val imgs = fileIds.map { android.util.Base64.encodeToString(Uploads.localImage(svc, it)!!.readBytes(), android.util.Base64.NO_WRAP) }
                    val ans = LlmClient(Prefs.base(svc), Prefs.model(svc), Prefs.apiKey(svc)).visionMany(
                        "너는 사용자가 보낸 사진 ${imgs.size}장을 대신 보는 눈이다(순서대로 1~${imgs.size}). 한국어로 사실만, 보이는 것만 답한다. 질문: $q", imgs)
                    "받은 이미지 ${imgs.size}장을 본 결과: ${ans.ifBlank { "(답 없음)" }}"
                } else {
                    val img = svc.screenshotJpegBase64() ?: throw IllegalStateException("스크린샷 실패(보안 화면이거나 권한 없음)")
                    val ans = LlmClient(Prefs.base(svc), Prefs.model(svc), Prefs.apiKey(svc)).vision(
                        "너는 안드로이드 폰 화면을 대신 보는 눈이다. 한국어로 사실만, 보이는 것만 답한다. 질문: $q", img)
                    "눈으로 본 결과: ${ans.ifBlank { "(답 없음)" }}"
                }
            } catch (e: Exception) { "look 실패: ${e.message}" }
            steps.put(JSONObject().put("tool", "look").put("summary", q.take(40)))
            trace.event("tool", JSONObject().put("step", step).put("name", name).put("args", a).put("outcome", r).put("ms", SystemClock.uptimeMillis() - t0v))
            svc.emit("step", "look: ${r.take(70)}")
            return r
        }
        if (name == "note") {
            val t = a.optString("text").trim()
            if (t.isNotEmpty()) memo += t
            steps.put(JSONObject().put("tool", "note").put("summary", t.take(40)))
            trace.event("tool", JSONObject().put("step", step).put("name", name).put("args", a).put("outcome", "메모 ${memo.size}개"))
            return "메모함 (${memo.size}개)"
        }
        if (name == "list_apps") {
            val r = listApps(a.optString("query"))
            trace.event("tool", JSONObject().put("step", step).put("name", name).put("args", a).put("outcome", r))
            return r
        }
        // 알림·문자 읽기 — 화면을 안 쓴다. 🔒 결과 본문은 모델에게만: trace·emit·steps 에는 첫 줄(건수·조건)만
        if (name in PRIVATE_READ_TOOLS) {
            val r = try {
                when (name) {
                    "sms_read" -> SmsReader.read(svc, a.optString("with"), a.optString("query"), a.optInt("limit", 10), a.optInt("since_days", 0))
                    else -> NotifyListener.query(svc, a.optString("app"), a.optInt("since_min", 60), a.optInt("limit", 10), a.optString("query"))
                }
            } catch (e: Exception) { "실패: ${e.message}" }
            val head = r.lineSequence().first().take(200)
            failStreak = if (r.startsWith("실패")) failStreak + 1 else 0
            steps.put(JSONObject().put("tool", name).put("summary", summarize(name, a, head)))
            trace.event("tool", JSONObject().put("step", step).put("name", name).put("args", a).put("outcome", head).put("ms", SystemClock.uptimeMillis() - t0))
            Log.i(TAG, "$name: ${head.substringBefore('(')}")
            svc.emit("step", "$name: ${head.take(70)}")
            return r
        }
        if (name == "save_to_downloads") {
            val r = try { DownloadSaver.save(svc, knownAttachments(), a.optString("file"), a.optString("name")) } catch (e: Exception) { "실패: ${e.message}" }
            failStreak = if (r.startsWith("실패")) failStreak + 1 else 0
            steps.put(JSONObject().put("tool", name).put("summary", summarize(name, a, r)))
            trace.event("tool", JSONObject().put("step", step).put("name", name).put("args", a).put("outcome", r).put("ms", SystemClock.uptimeMillis() - t0))
            svc.emit("step", "$name: ${r.take(70)}")
            return r
        }
        // 화면을 바꾸지 않는 기기 제어 — 화면을 다시 읽으면 「화면 변화 없음」 경고가 붙어 모델이 실패로 오해한다
        if (name in setOf("media", "volume", "flashlight", "brightness", "sound_mode", "clipboard", "system_setting")) {
            val r = try {
                when (name) {
                    "media" -> Device.media(svc, a.optString("action"))
                    "volume" -> Device.volume(svc, a.optString("stream", "media"), a.optString("action"), a.optInt("percent", -1))
                    "brightness" -> Device.brightness(svc, a.optString("action"), a.optInt("percent", -1))
                    "sound_mode" -> Device.soundMode(svc, a.optString("mode"))
                    "clipboard" -> Device.clipboard(svc, a.optString("action"), a.optString("text"))
                    "system_setting" -> systemSetting(a.optString("action"), a.optString("key"), a.optString("value"))
                    else -> Device.flashlight(svc, a.optBoolean("on", true))
                }
            } catch (e: Exception) { "실패: ${e.message}" }
            failStreak = if (r.startsWith("실패")) failStreak + 1 else 0
            steps.put(JSONObject().put("tool", name).put("summary", summarize(name, a, r)))
            trace.event("tool", JSONObject().put("step", step).put("name", name).put("args", a).put("outcome", r).put("ms", SystemClock.uptimeMillis() - t0))
            svc.emit("step", "$name: ${r.take(70)}")
            return r
        }
        var outcome = try {
            when (name) {
                "open_app" -> openApp(a.optString("name"))
                "tap" -> tap(a.optInt("index", -1), a.optBoolean("irreversible", false), long = false)
                "long_press" -> tap(a.optInt("index", -1), false, long = true)
                "type_text" -> typeText(a.optInt("index", -1), a.optString("text"), a.optBoolean("submit", false), a.optBoolean("irreversible", false))
                "scroll" -> scroll(a.optInt("index", -1), a.optString("direction", "down"))
                "global" -> global(a.optString("action"))
                "set_alarm" -> setAlarm(a.optInt("hour", -1), a.optInt("minute", 0), a.optString("label"))
                "set_timer" -> setTimer(a.optInt("seconds", 0), a.optString("label"))
                "open_link" -> openLink(a.optString("uri"), a.optString("package"))
                "open_settings" -> openSettings(a.optString("page"), a.optString("package"))
                "open_screen" -> openScreen(a)
                "write_note" -> NotesWriter(svc, trace).write(a.optString("title"), a.optJSONArray("blocks") ?: JSONArray(), append = a.optString("mode") == "append")
                    .also { noteWriteFailed = it.startsWith("노트 쓰기 중단") }
                    .let { if (it.startsWith("노트 쓰기 중단")) "$it\n→ write_note 를 다시 부르지 말 것(새 노트가 또 생긴다). 그대로 finish(success=false)로 무엇이 됐고 무엇이 안 됐는지 말한다" else it }
                "trash_captures" -> trashCaptures(a.optJSONArray("files")?.let { j -> (0 until j.length()).map { j.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(), a.optBoolean("all", false))
                "share_images" -> shareImages(a.optJSONArray("files")?.let { j -> (0 until j.length()).map { j.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(), a.optString("package"))
                "capture" -> capture(a.optInt("from_index", -1), a.optInt("to_index", -1), a.optString("name"),
                    a.optJSONArray("include")?.let { j -> (0 until j.length()).map { j.optString(it) }.filter { it.isNotBlank() } } ?: emptyList())
                "quick_toggle" -> quickToggle(a.optString("tile"), a.optBoolean("on", true))
                "stopwatch" -> stopwatch(a.optString("action"))
                "swipe" -> swipeTool(a.optString("direction"), a.optInt("index", -1), a.optBoolean("from_edge", false))
                "geocode" -> return geocodeTool(step, a)
                "wait" -> { Thread.sleep((a.optDouble("seconds", 1.0).coerceIn(0.5, 5.0) * 1000).toLong()); "기다림" }
                else -> "알 수 없는 도구: $name"
            }
        } catch (e: Exception) {
            "실패: ${e.message}"
        }
        failStreak = if (outcome.startsWith("실패") || outcome.contains(" 실패") || outcome.startsWith("없는 ") || outcome.contains("못 찾음")) failStreak + 1 else 0
        svc.status("▶ ${outcome.take(60)}")
        svc.emit("step", "${name}: ${outcome.lineSequence().first().take(70)}")
        svc.waitIdle()
        var s = ScreenReader.read(svc)
        // 웹뷰가 아직 로딩 중이면 항목이 거의 없다(예: 토스 「토스뱅크로 이동했어요」). 모델이 이걸 「보안 차단」으로 오판해
        // 포기했다(2026-09-26) — 넘기기 전에 최대 3초 더 기다렸다 다시 읽는다.
        var waited = 0
        // 거의 빈 화면(항목 2개 이하)도 로딩으로 본다 — 토스 거래 상세가 처음엔 「위로 이동」만 보여 곧바로 back 했다가 다시 여는 걸 반복했다(QA-T1 34단계)
        while (waited < 3 && ((s.text.contains("WebView") && s.nodes.size <= 6) || (s.nodes.size <= 2 && name in setOf("tap", "open_link", "open_app")))) {
            Thread.sleep(1000); waited++
            s = ScreenReader.read(svc)
        }
        if (waited > 0) trace.event("webview_wait", JSONObject().put("step", step).put("sec", waited).put("nodes", s.nodes.size))
        snap = s
        val changed = before == null || before.text != s.text
        learnAlias(s.pkg)

        // 진척 감시: 화면을 못 바꾼 같은 동작이 반복되면 경고 → 중단
        val key = "$name $a"
        // quick_toggle 은 패널을 열었다 닫아 원래 화면으로 돌아오는 게 정상 — 「화면 변화 없음」을 붙이면 실패로 오해한다
        // 입력기(커서 앞 글자)로 들어간 게 확인된 입력은 트리가 늦게 갱신돼도 경고를 붙이지 않는다 — 붙였더니 모델이 실패로 믿고
        // 이어 쓰기를 한 번 더 해 빈 줄이 쌓였다(2026-09-26 run 173855-202)
        val typedVerified = name == "type_text" && (outcome.contains("(키보드 입력") || outcome.startsWith("이어 쓰기:")) && !outcome.contains("읽을 수 없")
        if (!changed && typedVerified) {
            outcome += "\n(입력은 편집기에서 확인됨 — 화면 목록은 늦게 갱신될 수 있으니 다시 넣지 말 것)"
        } else if (!changed && name !in setOf("wait", "quick_toggle", "capture", "trash_captures")) {   // capture 는 화면을 안 바꾸는 게 정상
            repeatCount = if (key == repeatKey) repeatCount + 1 else 1
            repeatKey = key
            outcome += "\n⚠️ 화면 변화 없음"
            lastUnchangedWarn = true
            if (repeatCount >= WARN_REPEATS) {
                lastGuard = "$name 이 ${repeatCount}번째 화면 변화 없음"
                outcome += " — 같은 동작이 ${repeatCount}번째 효과가 없다. 반드시 다른 방법을 쓰거나 finish 할 것."
                trace.event("guard", JSONObject().put("step", step).put("key", key).put("count", repeatCount))
            }
        } else if (changed) {
            repeatKey = ""; repeatCount = 0
        }
        // 화면이 바뀌어도(목록↔상세 왕복) 같은 동작을 거듭하면 헛돈다 — 최근 10단계에서 센다. 스크롤·대기·뒤로는 반복이 정상이라 뺀다
        // scroll 도 센다 — 없는 항목을 찾아 같은 두 화면을 아래·위로 16번 오가도 안 잡혔다(2026-09-26 23:25 「충전 중 화면 켜짐」, 사용자가 수동 정지).
        // 한 방향 스크롤은 결과 화면이 매번 달라 걸리지 않는다
        if (name !in setOf("wait", "global")) {
            // 같은 동작이 「같은 결과 화면」으로 돌아올 때만 헛도는 것으로 센다. 동작만 세면 노트에 항목마다 「삽입→이미지」를 누르는
            // 정상 반복도 경고를 받았다(2026-09-26 QA — 상품 5개면 중단됐을 것). 목록↔같은 상세 왕복(토스 사례)은 결과 화면이 같아 그대로 잡힌다
            actionHistory += "$key#${s.text.hashCode()}"
            val n = actionHistory.takeLast(10).count { it == "$key#${s.text.hashCode()}" }
            if (n >= 3) {
                lastGuard = "$name 을 같은 결과 화면으로 최근 ${n}번(화면을 오가며 헛돎)"
                outcome += "\n⚠️ 같은 동작을 최근 ${n}번 했다(화면을 오가며 헛돌고 있음). 이미 본 내용은 note 에 적고, 다른 항목을 고르거나 finish 할 것."
                trace.event("guard", JSONObject().put("step", step).put("key", key).put("count", n).put("kind", "oscillation"))
            }
            if (n >= 5) oscillating = true
        }
        // 본 화면 기억: 화면이 실제로 바뀌었는데 이미 본 화면이면 구조만 남긴다(라벨 없음 — 세션 기록에 남아도 된다)
        lastChanged = changed
        lastVisit = recordSeen(step, name, s, changed)
        lastVisit?.takeIf { it.revisit }?.let { outcome += "\n⟲ 이미 본 화면(${SeenScreens.stepsText(it.prevSteps)}단계와 같음)" }

        steps.put(JSONObject().put("tool", name).put("summary", summarize(name, a, outcome)))
        trace.event("tool", JSONObject().put("step", step).put("name", name).put("args", a).put("outcome", outcome)
            .put("changed", changed).put("pkg", s.pkg).put("ms", SystemClock.uptimeMillis() - t0))
        return "$outcome${appTips(s.pkg)}\n\n$SCREEN_MARK\n${s.text}"
    }

    /** 이번 실행에서 처음 들어온 앱이면 그 앱의 요령을 한 번 붙인다. */
    private fun appTips(pkg: String): String {
        if (!notedPkgs.add(pkg)) return ""
        val n = kb.appNotes(pkg) ?: return ""
        trace.event("app_notes", JSONObject().put("pkg", pkg).put("chars", n.length))
        return "\n\n[앱 요령: $pkg]\n$n"
    }

    private fun summarize(name: String, a: JSONObject, outcome: String): String = when (name) {
        "open_app" -> outcome.substringAfter("앱 열기: ", a.optString("name")).substringBefore(" (")
        "tap", "long_press" -> "「" + outcome.substringAfter(": ").substringBefore("\n").take(30) + "」"
        "type_text" -> "「${a.optString("text")}」" + if (a.optBoolean("submit")) " 완료키" else ""
        "scroll" -> a.optString("direction")
        "global" -> a.optString("action")
        "set_alarm" -> "%02d:%02d".format(a.optInt("hour"), a.optInt("minute"))
        "set_timer" -> "${a.optInt("seconds")}초"
        "open_link" -> a.optString("uri").take(60)
        "open_settings" -> a.optString("page") + a.optString("package").let { if (it.isBlank()) "" else " $it" }
        "media" -> a.optString("action")
        "volume" -> "${a.optString("stream", "media")} ${a.optString("action")}" + if (a.has("percent")) " ${a.optInt("percent")}%" else ""
        "flashlight" -> if (a.optBoolean("on", true)) "on" else "off"
        "brightness" -> a.optString("action") + if (a.has("percent")) " ${a.optInt("percent")}%" else ""
        "sound_mode" -> a.optString("mode")
        "write_note" -> (if (a.optString("mode") == "append") "이어쓰기 " else "") + "「${a.optString("title")}」 블록 ${a.optJSONArray("blocks")?.length() ?: 0}개"
        "share_images" -> "${a.optJSONArray("files")?.length() ?: 0}장 → ${a.optString("package").ifBlank { "공유 창" }}"
        "trash_captures" -> if (a.optBoolean("all")) "캡처 전부" else "${a.optJSONArray("files")?.length() ?: 0}개 이름"
        "capture" -> (if (a.has("from_index")) "[${a.optInt("from_index")}~${a.optInt("to_index", a.optInt("from_index"))}] " else "전체 ") + a.optString("name")
        "quick_toggle" -> a.optString("tile") + if (a.optBoolean("on", true)) " on" else " off"
        "stopwatch" -> a.optString("action")
        "clipboard" -> a.optString("action") + a.optString("text").let { if (it.isBlank()) "" else " 「${it.take(30)}」" }
        "open_screen" -> a.optString("name") + a.optString("title").let { if (it.isBlank()) "" else " 「$it」" }
        "swipe" -> a.optString("direction") + (if (a.has("index")) " [${a.optInt("index")}]" else "") + if (a.optBoolean("from_edge")) " 가장자리" else ""
        "geocode" -> a.optString("query")
        "save_to_downloads" -> a.optString("file").take(40) + " → " + if (outcome.startsWith("실패")) "실패" else outcome.substringBefore(" 에 저장")
        "sms_read" -> listOf(a.optString("with"), a.optString("query")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "전체" } + " → " + outcome.substringBefore('(').trim()
        "notifications" -> listOf(a.optString("app"), a.optString("query")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "전체" } + " → " + outcome.substringBefore('(').trim()
        in AssistantTools.NAMES -> AssistantTools.summarize(name, a, outcome)
        in OaiTools.NAMES -> OaiTools.summarize(name, a, outcome)
        in CapTools.NAMES -> CapTools.summarize(name, a, outcome)
        "system_setting" -> a.optString("action") + " " + a.optString("key").ifBlank { "전체" } + a.optString("value").let { if (it.isBlank()) "" else "=$it" }
        else -> a.toString().take(40)
    }

    /** open_app 이 실패한 이름이 있는데 결국 어떤 앱에 도착했다면, 그 이름을 그 앱의 별칭으로 배운다. */
    private fun learnAlias(pkg: String) {
        if (failedAppNames.isEmpty() || pkg == "?" || pkg == homePkg || pkg == svc.packageName) return
        if (launcherApps().none { it.first.activityInfo.packageName == pkg }) return
        failedAppNames.forEach { n ->
            kb.addAlias(n, pkg)
            trace.event("learn", JSONObject().put("kind", "alias").put("name", n).put("pkg", pkg))
            Log.i(TAG, "learned alias $n → $pkg")
        }
        failedAppNames.clear()
    }

    /** 도구를 하나도 안 부르고 성공으로 끝내려는 첫 시도인가 — 동작 요청일 때만. 한 번만 되묻는다 */
    /**
     * 방법 고르기 — 선택 카드를 띄우고 고른 방법을 돌려준다(사용자 요청 2026-09-28: 웹 검색만 하던 것 → 유튜브 앱에서 직접 등 길을 고르게).
     * 사용자는 「알아서 할 수 있는 걸 멈추고 묻는」 것을 싫어한다 → 한 실행 한 번 · 시한(기본 10초) 지나면 추천안 · 확인 창과 겹치면 곧바로 추천안.
     * 인자가 틀리면 카드 없이 「실패:」로 돌려준다(고쳐 다시 부르게).
     */
    private fun chooseWay(step: Int, a: JSONObject): String {
        val t0 = SystemClock.uptimeMillis()
        chosenWay?.let { return "이미 골랐다: $it — 그대로 진행" }
        val question = a.optString("question").trim()
        val arr = a.optJSONArray("options")
        val opts = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
            .map { it.optString("label").trim() to it.optString("detail").trim() }
        val rec = if (a.has("recommended")) a.optInt("recommended", -1) else -1
        val bad = when {
            question.isEmpty() -> "question 이 비었다"
            question.length > 60 -> "question 이 ${question.length}자 — 60자 이하로"
            arr == null || arr.length() !in 2..3 || opts.size != arr.length() -> "options 는 {label, detail} 2~3개"
            opts.any { it.first.isEmpty() } -> "label 이 빈 선택지가 있다"
            opts.any { it.first.length > 24 } -> "label 은 24자 이하"
            opts.any { it.second.length > 60 } -> "detail 은 60자 이하"
            rec !in opts.indices -> "recommended 는 0~${opts.size - 1}"
            else -> null
        }
        if (bad != null) {
            failStreak++
            trace.event("tool", JSONObject().put("step", step).put("name", "choose_way").put("args", a).put("outcome", "실패: $bad"))
            return "실패: choose_way 인자 오류 — $bad. 고쳐서 다시 부르거나 추천안으로 바로 진행할 것"
        }
        failStreak = 0
        val ch = if (!wayAllowed) AgentService.Choice(rec, "off")
            else {
                svc.status("🤔 방법 고르는 중…")
                svc.choose(question, opts, rec, Prefs.chooseWaitSec(svc) * 1000L)
            }
        val (picked, how) = ch
        val stopped = picked == AgentService.CHOSE_STOP
        // 직접 답하기(사용자 요청 2026-09-28) — 선택지 대신 사용자가 말하거나 쓴 글이 곧 방법·조건이다
        val reply = ch.reply?.takeIf { picked == AgentService.CHOSE_REPLY }
        val result = when {
            how == "cancel" || cancelled -> "사용자가 작업을 멈춤"
            stopped -> { wayStopped = true; "사용자가 그만둠 — finish 로 끝낼 것" }
            reply != null -> "사용자가 직접 답함: $reply — 이 지시대로 되묻지 말고 끝까지 할 것"
            how == "tap" -> "사용자가 고름: ${opts[picked].first} — 이 방법으로 되묻지 말고 끝까지 할 것"
            how == "timeout" -> "시간 안에 안 골라 추천안으로: ${opts[picked].first} — 되묻지 말고 끝까지 할 것"
            how == "reply_timeout" -> "직접 답하기에 60초 안에 답이 없어 추천안으로: ${opts[picked].first} — 되묻지 말고 끝까지 할 것"
            how == "reply_closed" -> "직접 답하기 창을 답 없이 닫아 추천안으로: ${opts[picked].first} — 되묻지 말고 끝까지 할 것"
            else -> "추천안으로(카드를 띄우지 않음: $how): ${opts[picked].first} — 되묻지 말고 끝까지 할 것"
        }
        if (!stopped) chosenWay = if (reply != null) "직접 답함: ${reply.take(80)}" else opts[picked].first
        steps.put(JSONObject().put("tool", "choose_way").put("summary", "${chosenWay ?: "그만"} ($how)"))
        trace.event("choice", JSONObject().put("step", step).put("question", question).put("labels", JSONArray(opts.map { it.first }))
            .put("recommended", rec).put("picked", when { stopped -> "그만"; reply != null -> -1; else -> opts[picked].first }).put("how", how)
            .apply { if (reply != null) put("reply", reply.take(200)) }
            .put("ms", SystemClock.uptimeMillis() - t0))
        trace.event("tool", JSONObject().put("step", step).put("name", "choose_way").put("args", a).put("outcome", result))
        svc.emit("step", "choose_way: ${result.take(70)}")
        return result
    }

    /**
     * 화면 없는 도구(문자·알림·검색·위임 등)만 쓰고 실패로 끝내려 한다 — 앱 화면으로 한 번 더 시도하게 되돌린다(한 실행 한 번).
     * 사용자 2026-09-28: 「하나의 도구로 안 되면 문자 앱·전화번호부에 직접 가보는 유연함이 있어야 에이전트」 — sms_read 0건 두 번에
     * 「번호를 알려 주면」으로 끝냈다. 맡긴 일이 도는 중·그만둠·이어가기(화면 없음)엔 걸지 않는다
     */
    private fun needsFallbackChallenge(): Boolean {
        if (fallbackChallenged || usedScreen || noScreen || wayStopped || handedOffThen || steps.length() == 0) return false
        fallbackChallenged = true
        trace.event("fallback_challenge", JSONObject().put("steps", steps.length()))
        return true
    }

    private fun needsZeroStepChallenge(): Boolean {
        if (zeroStepChallenged || steps.length() > 0 || !ACTION_REQ.containsMatchIn(task)) return false
        zeroStepChallenged = true
        trace.event("zero_step_challenge", JSONObject().put("request", task))
        return true
    }

    private fun node(i: Int): AccessibilityNodeInfo =
        snap?.nodes?.getOrNull(i) ?: throw IllegalArgumentException("[$i] 번 항목 없음 — 가장 최근 화면의 번호를 쓸 것")

    private fun gate(label: String, what: String): Boolean {
        val ok = svc.confirm("「$label」 $what 할까요?")
        trace.event("confirm", JSONObject().put("label", label).put("what", what).put("ok", ok))
        return ok
    }

    /**
     * 좌표로 누를 지점 — 가운데가 떠 있는 버튼에 덮여 있으면 대상 안의 안 덮인 곳을 고른다.
     * 펼친 화면 네이버 지도에서 「덕수궁 궁궐」 줄 한가운데에 「지도보기」 버튼이 떠 있어, 줄 대신 그 버튼이 눌렸다(2026-09-26 U1·U2 21단계).
     * 덮개 판정: 그 지점을 포함하는 클릭 요소 중 대상의 조상도 자손도 아닌 것(목록 줄끼리는 안 겹치므로 겹치면 떠 있는 것)
     */
    private fun tapPoint(n: AccessibilityNodeInfo, r: Rect): Pair<Float, Float> {
        fun related(o: AccessibilityNodeInfo): Boolean {
            var c: AccessibilityNodeInfo? = n; while (c != null) { if (c == o) return true; c = c.parent }
            c = o; while (c != null) { if (c == n) return true; c = c.parent }
            return false
        }
        val others = findAll { it.isClickable && it.isVisibleToUser && it.packageName == n.packageName }
            .map { it to Rect().also { rr -> it.getBoundsInScreen(rr) } }
            .filter { (o, rr) -> !rr.isEmpty && Rect.intersects(rr, r) && !related(o) }
        if (others.isEmpty()) return r.exactCenterX() to r.exactCenterY()
        val cands = listOf(0.5f to 0.5f, 0.2f to 0.5f, 0.8f to 0.5f, 0.5f to 0.25f, 0.5f to 0.75f, 0.12f to 0.3f, 0.88f to 0.3f, 0.12f to 0.7f, 0.88f to 0.7f)
            .map { (fx, fy) -> r.left + r.width() * fx to r.top + r.height() * fy }
        val free = cands.firstOrNull { (x, y) -> others.none { (_, rr) -> rr.contains(x.toInt(), y.toInt()) } }
        if (free != null && free != cands[0]) trace.event("tap_point_moved", JSONObject().put("label", ScreenReader.labelOf(n).take(30))
            .put("covered_by", others.first { (_, rr) -> rr.contains(cands[0].first.toInt(), cands[0].second.toInt()) }.let { ScreenReader.labelOf(it.first).take(20) }))
        return free ?: (r.exactCenterX() to r.exactCenterY())
    }

    private fun tap(i: Int, irreversible: Boolean, long: Boolean): String {
        val n = node(i)
        // 검색창 지우기 버튼은 설명이 「삭제」다 — 확인 게이트가 오탐하고, 모델은 이걸 누른 뒤 「노트를 삭제했다」고 거짓 보고했다(2026-09-26 정리 작업)
        val searchClear = n.viewIdResourceName?.let { it.endsWith("search_close_btn") || it.endsWith("search_clear") || it.endsWith("btn_clear") } == true
        val label = ScreenReader.labelOf(n).let { if (searchClear) "검색어 지우기(입력칸 비움 — 삭제 아님)" else it }
        val pkg = snap?.pkg ?: "?"
        val gated = !long && !searchClear && (irreversible || isRiskyAction(label))
        if (gated && !gate(label, "눌러도")) return "사용자가 거부함 — 이 동작은 하지 말 것"
        val r = Rect()
        n.getBoundsInScreen(r)
        val word = if (long) "길게 누름" else "탭"
        // 크기 0(접힌 목록 속 항목)이면 좌표 탭이 상자 가장자리를 눌러 헛돈다(E6 「화면 변화 없음」 2회) — 누르지 않고 알려 준다
        if (r.width() <= 0 || r.height() <= 0)
            return "실패: [$i] 「${label.take(20)}」은 지금 안 보인다(크기 0 — 접힌 목록 속). 목록을 펼치거나(scroll up·swipe up) 보이게 한 뒤 새 화면 번호로 누를 것"

        // 이 앱이 접근성 클릭을 무시한다고 이미 배웠다면 바로 좌표로
        if (kb.prefersGesture(pkg)) {
            val (px, py) = tapPoint(n, r)
            return if (svc.tapAt(px, py, if (long) 700 else 60)) "$word(좌표): $label" else "$word 실패: $label"
        }
        var t: AccessibilityNodeInfo? = n
        while (t != null && !(if (long) t.isLongClickable else t.isClickable)) t = t.parent
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        if (t != null && t.performAction(action)) {
            // 「성공」을 믿지 않는다 — 화면이 바뀌었는지 본다. 되돌릴 수 없는 동작은 두 번 누르면 안 되므로 재시도하지 않는다.
            svc.waitIdle()
            val after = ScreenReader.read(svc)
            if (after.text != snap?.text || gated) return "$word: $label"
            val (px, py) = tapPoint(n, r)
            if (svc.tapAt(px, py, if (long) 700 else 60)) {
                svc.waitIdle()
                if (ScreenReader.read(svc).text != after.text) {
                    kb.markGesture(pkg)
                    trace.event("learn", JSONObject().put("kind", "gesture").put("pkg", pkg).put("label", label))
                    Log.i(TAG, "learned gesture-tap for $pkg")
                    return "$word: $label (접근성 클릭이 무시돼 좌표로 다시 누름 — 이 앱은 앞으로 좌표 탭)"
                }
            }
            return "$word: $label"
        }
        val (px, py) = tapPoint(n, r)
        val ok = svc.tapAt(px, py, if (long) 700 else 60)
        return if (ok) "$word(좌표): $label" else "$word 실패: $label"
    }

    private fun typeText(i: Int, text: String, submit: Boolean, irreversible: Boolean): String {
        if (i < 0) {
            // 커서 자리에 이어 쓰기 — 본문을 눌러 포커스를 잡으면 커서가 누른 자리(글 중간)로 옮겨져, 노트 이미지가 상품명 한가운데 들어갔다(2026-09-26 run 172641-309)
            if (submit) return "실패: 이어 쓰기(index 생략)는 submit 없이만 — 보내기는 입력칸 번호를 주고 할 것"
            val r = commitVerified(text)
            trace.event("ime_commit", JSONObject().put("append", true).put("result", r).put("chars", text.length))
            return when (r) {
                "landed" -> "이어 쓰기: ${text.take(40)}"
                "unknown" -> "이어 쓰기: ${text.take(40)} (들어갔는지 읽을 수 없음 — 다시 넣지 말 것)"
                "missing" -> "이어 쓰기가 안 들어감(커서 앞 글자에 없음) — 입력칸 번호를 주고 type_text 할 것"
                else -> "실패: 지금 입력 중인 칸이 없음(키보드가 닫혔을 수 있음) — 입력칸 번호를 주고 type_text 할 것"
            }
        }
        var n = node(i)
        // 이미지(개체 문자 U+FFFC)가 든 입력칸을 덮어쓰면 이미지까지 지워진다 — 노트 본문을 type_text 로 덮어써 이미지 0장이 됐다(2026-09-26 run 175033-713)
        if (n.text?.contains('\uFFFC') == true) return "거부: 이 입력칸에는 이미지가 있어 덮어쓰면 지워진다 — 이어 쓰려면 index 없이 type_text, 노트 정리는 write_note"
        if (!n.isEditable) {
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(300)
            n = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: throw IllegalStateException("[$i] 는 입력칸이 아님")
        }
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        var how = ""
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            // 서식 편집기(삼성 노트 등)는 SET_TEXT 를 거부하거나, 막 열린 직후엔 입력 준비(커서·키보드)가 안 돼 붙여넣기도 거부한다
            // (2026-09-26: 새 노트 연 직후 497자 붙여넣기 실패). 눌러서 커서를 잡고, 준비될 때까지 기다리며 두 방법을 번갈아 몇 번 더.
            var ok = false
            for (attempt in 0 until 3) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(500L + attempt * 400L)
                val target = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: n
                if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { how = "(다시 넣기 ${attempt + 1})"; ok = true; break }
                // 키보드처럼 입력 — 클립보드·SET_TEXT 없이. 기존 내용을 바꾸려면 먼저 전체 선택해 덮어쓴다
                val cur = target.text?.toString().orEmpty()
                if (cur.isNotEmpty()) target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cur.length)
                })
                // 들어갔는지는 입력기 연결에서 읽은 커서 앞 글자로 본다. 입력칸 글자·화면 트리로 보다가 삼성 노트에서
                // 「안 들어감」 오판 → 다시 넣기로 본문이 2~3중으로 적혔다(2026-09-26 run 170426-933·173214-564·173438-544).
                // 원칙: 입력 명령이 먹힌 뒤엔, 「읽어 봤는데 없다」가 확인될 때만 다른 방법으로 넘어간다. 확인 불가면 들어간 것으로 친다.
                val r = commitVerified(text)
                trace.event("ime_commit", JSONObject().put("attempt", attempt).put("result", r).put("chars", text.length).put("before", cur.length))
                if (r == "landed") { how = "(키보드 입력 ${attempt + 1})"; ok = true; break }
                if (r == "unknown") { how = "(키보드 입력 ${attempt + 1} — 들어갔는지 읽을 수 없어 다시 넣지 않음)"; ok = true; break }
                // missing·nocommit 일 때만 붙여넣기. 붙여넣기도 성공 신호가 아니라 커서 앞 글자로 확인한다(삼성 노트는 성공을 돌려주고 안 넣기도 한다)
                if (pasteInto(target, text) && pastedVerified(text)) { how = "(붙여넣기 ${attempt + 1})"; ok = true; break }
            }
            trace.event("type_retry", JSONObject().put("ok", ok).put("how", how).put("chars", text.length))
            if (!ok) return "자동 입력 실패로 판정 — 대신 글을 클립보드에 넣어 두었다. 서식 편집기는 들어갔는데 못 읽는 경우가 있으니 먼저 look 으로 본문에 이미 글이 있는지 확인하고, 없을 때만 입력칸을 long_press → 「붙여넣기」 tap(같은 type_text 를 되풀이하지 말 것 — 중복 입력된다)"
        }
        if (!submit) return "입력$how: $text"
        if (irreversible && !gate(text, "보내도")) return "입력은 했지만 사용자가 전송을 거부함"
        val enter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        return if (n.performAction(enter)) "입력 후 완료키: $text" else "입력: $text (완료키 실패 — 화면의 버튼을 눌러야 함)"
    }

    /**
     * 키보드식 입력 한 번 + 확인. 확인은 입력기 연결에서 읽은 커서 앞 글자로(접근성 트리는 삼성 노트에서 2초 넘게 늦어 양쪽으로 오판했다).
     * @return landed(들어감 확인) · unknown(명령은 먹었는데 읽을 수 없음 — 들어간 것으로 치고 절대 다시 넣지 않는다) · missing(읽었는데 없음) · nocommit(입력 연결 없음)
     */
    private fun commitVerified(text: String): String {
        fun norm(x: String) = x.replace(Regex("\\s+"), "")
        val tail = norm(text).takeLast(15)
        if (!svc.commitTextViaIme(text)) return "nocommit"
        if (tail.isEmpty()) return "landed"   // 줄바꿈만 넣은 경우
        var readable = false
        repeat(8) {
            Thread.sleep(250)
            val b = svc.textBeforeCursor(text.length + 60)
            if (b != null) { readable = true; if (norm(b).endsWith(tail)) return "landed" }
        }
        return if (readable) "missing" else "unknown"
    }

    private fun pastedVerified(text: String): Boolean {
        fun norm(x: String) = x.replace(Regex("\\s+"), "")
        val tail = norm(text).takeLast(15)
        repeat(6) {
            Thread.sleep(300)
            val b = svc.textBeforeCursor(text.length + 60) ?: return@repeat
            if (norm(b).endsWith(tail)) return true
        }
        return false
    }

    /** 붙여넣기 실패 뒤에도 클립보드에 글을 남겨 둘지 — 모델이 길게 눌러 「붙여넣기」 메뉴로 직접 붙일 수 있게 */
    private var keepClipForManualPaste = false

    private fun pasteInto(target: AccessibilityNodeInfo, text: String): Boolean {
        val cm = svc.getSystemService(android.content.ClipboardManager::class.java) ?: return false
        val old = runCatching { cm.primaryClip }.getOrNull()   // 백그라운드라 대개 null(읽기 차단) — 그땐 복원 안 함
        var ok = false
        val info = JSONObject().put("chars", text.length)
        try {
            val set = runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("foldagent", text)); true }
                .getOrElse { info.put("setError", it.toString()); false }
            info.put("clipSet", set)
            Thread.sleep(150)
            // 디버그 경로에서 확실히 된 순서: 눌러서 커서 → 0.6초 → 포커스 받은 칸에 붙여넣기(안 되면 대상 칸에)
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(600)
            val focused = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            info.put("focusedIsTarget", focused == target).put("focusedActions", JSONArray(focused?.actionList?.map { it.id } ?: emptyList<Int>()))
                .put("targetActions", JSONArray(target.actionList.map { it.id }))
            ok = focused?.performAction(AccessibilityNodeInfo.ACTION_PASTE) == true || target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            info.put("ok", ok)
            return ok
        } finally {
            trace.event("paste", info)
            // 실패했으면 글을 클립보드에 남겨 둔다(수동 붙여넣기 폴백). 성공했고 예전 내용을 읽을 수 있었으면 되돌린다.
            if (!ok) keepClipForManualPaste = true
            else if (old != null) runCatching { cm.setPrimaryClip(old) }
        }
    }

    private fun scroll(i: Int, dir: String): String {
        val forward = dir == "down" || dir == "right"
        val target = if (i >= 0) node(i) else snap?.nodes?.filter { it.isScrollable }?.maxByOrNull {
            val r = Rect(); it.getBoundsInScreen(r); r.width() * r.height()
        }
        val act = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (target != null && target.performAction(act)) return "스크롤 $dir"
        val dm = svc.resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val ok = when (dir) {
            "down" -> svc.swipe(w / 2, h * 0.7f, w / 2, h * 0.3f)
            "up" -> svc.swipe(w / 2, h * 0.3f, w / 2, h * 0.7f)
            "right" -> svc.swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            else -> svc.swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
        }
        return if (ok) "스와이프 $dir" else "스크롤 실패"
    }

    private fun global(action: String): String {
        val id = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            // 스크린샷 도구가 없어 알림창을 14단계 뒤지다 실패했다(2026-09-26 run 153234-524)
            "screenshot" -> return if (svc.systemScreenshot()) "스크린샷 찍음 — 갤러리 「스크린샷」 앨범(DCIM/Screenshots)에 저장됨. 보안 화면(금융 앱 등)은 시스템이 막아 저장되지 않을 수 있다"
                else "스크린샷 실패"
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            // power_dialog 는 넣지 않는다 — 이 폰(One UI 9)은 전원 메뉴를 여는 순간 SystemUI 가 lockNow 로 폰을 잠가 이후 조작이 막힌다(2026-09-26 실측)
            else -> return "알 수 없는 버튼: $action"
        }
        if (action == "lock_screen") {
            return if (svc.performGlobalAction(id)) "화면을 잠갔다 — 잠금 해제는 사용자만 할 수 있어 더 조작할 수 없다. 바로 finish 할 것" else "화면 잠금 실패"
        }
        return if (svc.performGlobalAction(id)) "시스템 버튼: $action" else "시스템 버튼 실패: $action"
    }

    /** 모든 창(퀵 패널은 SystemUI 창)에서 조건에 맞는 항목을 찾는다 */
    private fun findAll(pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (pred(n)) out += n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        svc.windows.forEach { walk(it.root) }
        if (out.isEmpty()) walk(svc.rootInActiveWindow)
        return out
    }

    private fun area(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }.let { it.width() * it.height() }

    /**
     * 영역 캡처. 「상품명부터 가격까지」인데 상품명이 화면 위로 스크롤돼 있으면 그 줄 없이 찍혔다(2026-09-26 E4 — 모델이 「먼저 화면에 있는지 확인」을
     * 안 지키고 보이는 다른 줄을 from 으로 골랐다). 그래서 도구가 확인한다:
     *  - include(꼭 들어갈 글)를 트리 전체(화면 밖 포함)에서 찾아 영역에 넣는다. 화면 밖·가장자리에 걸렸으면 그쪽으로 조금 스크롤해 다시 본다
     *  - from/to 항목이 스크롤 영역 위·아래 가장자리에 붙어 있으면(잘렸을 수 있음) 같은 방식으로 맞춘다
     *  - 스크롤 2번으로도 안 맞거나 글이 트리에 아예 없으면 찍지 않고 거부 + 안내
     * 스크롤은 키보드가 떠 있으면 하지 않는다(키보드 위를 밀면 글자가 찍힌다).
     */
    private fun capture(from: Int, to: Int, name: String, include: List<String> = emptyList()): String {
        val dm = svc.resources.displayMetrics
        fun rectOf(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
        fun label(n: AccessibilityNodeInfo) = (n.text?.toString() ?: n.contentDescription?.toString()).orEmpty().replace(Regex("\\s+"), "")
        // 항목 번호는 스크롤 뒤 재활용(다른 내용)될 수 있다 — 글이 그대로인지로 같은 항목인지 본다
        val picked = listOfNotNull(if (from >= 0) node(from) else null, if (to >= 0) node(to) else null).map { it to label(it) }
        fun findText(t: String): AccessibilityNodeInfo? {
            val key = t.replace(Regex("\\s+"), "").take(20)
            if (key.isEmpty()) return null
            return findAll { n ->
                n.packageName != svc.packageName && label(n).let { l -> l.contains(key) || (l.length >= 4 && l.length * 2 >= key.length && key.contains(l)) }
            }.sortedBy { area(it) }.let { c -> c.firstOrNull { it.isVisibleToUser && !rectOf(it).isEmpty } ?: c.firstOrNull() }   // 보이는 것 먼저 — 접힌 제목 같은 숨은 노드를 골라 「화면 밖」으로 오판했다(A3)
        }
        fun scroller(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            var c = n?.parent
            while (c != null) { if (c.isScrollable) return c; c = c.parent }
            return null
        }
        /**
         * 스크롤 영역에서 실제로 보이는 부분 — 목록이 하단 탭바·상단 검색줄 뒤로까지 이어져 있어, 가려진 부분까지 「안」으로 보고
         * 쿠팡 하단 탭바를 함께 찍었다(2026-09-26 X1). 영역 밖(자손 아님) 요소 중 영역 위·아래 가장자리를 덮는 얇은 막대만큼 줄인다.
         */
        fun visibleArea(sc: AccessibilityNodeInfo): Rect {
            val box = rectOf(sc)
            fun inside(n: AccessibilityNodeInfo): Boolean { var c: AccessibilityNodeInfo? = n; while (c != null) { if (c == sc) return true; c = c.parent }; return false }
            val bars = findAll { n -> n.packageName == sc.packageName && n.isVisibleToUser && (n.isClickable || !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()) }
                .map { it to rectOf(it) }
                .filter { (n, r) -> !r.isEmpty && Rect.intersects(r, box) && r.height() < box.height() / 5 && !inside(n) }
            val out = Rect(box)
            for ((_, r) in bars) {
                if (r.centerY() > box.centerY()) out.bottom = minOf(out.bottom, r.top) else out.top = maxOf(out.top, r.bottom)
            }
            return if (out.height() > box.height() / 3) out else box
        }
        val log = JSONObject().put("from", from).put("to", to).put("include", JSONArray(include))
        var scrolled = 0
        var crop: Rect? = null
        var area: Rect? = null   // 스크롤 영역의 실제로 보이는 부분(가리는 막대 제외)
        while (true) {
            var need: String? = null
            var why = ""
            val alive = picked.filter { (n, l) -> n.refresh() && label(n) == l }.map { it.first }
            if (picked.isNotEmpty() && alive.size < picked.size && include.isEmpty())
                return "실패: 스크롤 뒤 항목 [$from~$to] 이 화면에서 바뀜 — 새 화면 번호로 다시 capture(include 에 처음·끝 글을 주면 도구가 맞춘다)"
            val sc = scroller(alive.firstOrNull() ?: include.firstNotNullOfOrNull { findText(it) })
            val cont = sc?.let { visibleArea(it) }
            // 가장자리에 붙어 있어도 그쪽으로 더 스크롤할 수 없으면(페이지 맨 위·맨 아래) 잘린 게 아니다
            val canUp = sc?.actionList?.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD) == true
            val canDown = sc?.actionList?.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD) == true
            val rects = ArrayList<Rect>()
            for ((k, n) in alive.withIndex()) {
                val r = rectOf(n); rects += r
                if (cont != null && canUp && k == 0 && r.top <= cont.top + 2) { need = "up"; why = "처음 항목이 위쪽 가장자리에 걸림" }
                if (cont != null && canDown && k == alive.lastIndex && r.bottom >= cont.bottom - 2 && need == null) { need = "down"; why = "끝 항목이 아래쪽 가장자리에 걸림" }
            }
            for ((k, t) in include.withIndex()) {
                val n = findText(t)
                if (n == null) {
                    if (need == null) { need = if (k == 0 && include.size > 1) "up" else if (k == include.lastIndex && include.size > 1) "down" else "up"; why = "「${t.take(15)}」이 화면에 없음" }
                    continue
                }
                val r = rectOf(n)
                val box = cont ?: Rect(0, 0, dm.widthPixels, dm.heightPixels)
                when {
                    !n.isVisibleToUser || r.isEmpty -> if (need == null) {
                        need = if (r.bottom <= box.top || r.top < 0 || (r.isEmpty && k == 0)) "up" else "down"; why = "「${t.take(15)}」이 화면 밖"
                    }
                    cont != null && canUp && r.top <= cont.top + 2 && k == 0 -> if (need == null) { need = "up"; why = "「${t.take(15)}」이 위쪽 가장자리에 잘림" }
                    cont != null && canDown && r.bottom >= cont.bottom - 2 && k == include.lastIndex -> if (need == null) { need = "down"; why = "「${t.take(15)}」이 아래쪽 가장자리에 잘림" }
                    else -> rects += r
                }
            }
            if (need == null) {
                crop = rects.reduceOrNull { a, b -> Rect(a).apply { union(b) } }
                area = cont
                log.put("area", cont?.toShortString())
                break
            }
            log.put("scroll$scrolled", "$need: $why")
            // 가장자리에 걸렸지만 더 스크롤할 수 없는 경우(목록 맨 위 등)는 스크롤해도 화면이 안 바뀐다 → 두 번째 시도 뒤 거부
            if (scrolled >= 2 || cont == null) {
                trace.event("capture_check", log.put("ok", false))
                return "거부: $why — 찍지 않음. scroll ${if (need == "up") "up" else "down"} 으로 담을 것의 처음·끝이 다 보이게 한 뒤 새 화면 번호로 다시 capture"
            }
            if (svc.keyboardShown()) {
                trace.event("capture_check", log.put("ok", false).put("keyboard", true))
                return "거부: $why — 키보드가 떠 있어 스크롤하지 않음. global back 으로 키보드를 닫고 다시 capture"
            }
            val x = cont.exactCenterX(); val h = cont.height() * 0.3f
            val y1 = cont.top + cont.height() * 0.35f
            if (need == "up") svc.swipe(x, y1, x, y1 + h, 700) else svc.swipe(x, y1 + h, x, y1, 700)   // 천천히 짧게 — 관성으로 멀리 가지 않게
            Thread.sleep(900)
            scrolled++
        }
        crop?.apply {
            // 세로만 항목으로 정하고 가로는 화면 전체 — 두 항목을 감싼 상자를 그대로 쓰니 오른쪽이 잘렸다(2026-09-26 E4 「Shokz Open Dots On…」)
            left = 0; right = dm.widthPixels
            inset(0, -12)   // 위아래 글자가 잘리지 않게 약간 여유
            area?.let { v -> top = maxOf(top, v.top); bottom = minOf(bottom, v.bottom) }   // 여유를 붙인 뒤 가리는 막대만큼은 잘라 낸다
        }
        trace.event("capture_check", log.put("ok", true).put("scrolled", scrolled).put("crop", crop?.toShortString()))
        if (crop != null && (crop.width() < 40 || crop.height() < 40)) return "실패: 영역이 너무 작음(${crop.width()}x${crop.height()}) — 더 큰 항목(묶음)의 번호를 쓸 것"
        val bmp = svc.captureBitmap() ?: return "실패: 캡처 안 됨(보안 화면일 수 있음)"
        val path = svc.saveCapture(bmp, crop, name) ?: return "실패: 저장 안 됨"
        val size = crop?.let { "${it.width()}x${it.height()} 영역" } ?: "전체 ${bmp.width}x${bmp.height}"
        val moved = if (scrolled > 0) " · 담을 부분이 다 보이게 ${scrolled}번 스크롤한 뒤 찍음" else ""
        return "캡처 저장: $path ($size)$moved — 갤러리 「스크린샷」 맨 앞에 있다"
    }

    /** 타일의 실제 상태를 시스템 값으로 — 타일 모양은 못 믿는다(자동 회전 타일이 「세로 고정」인데 checked, 블루투스는 검색 창에 가려 못 읽음) */
    private fun systemState(tile: String): Boolean? = runCatching {
        val cr = svc.contentResolver
        when (tile) {
            "bluetooth" -> android.provider.Settings.Global.getInt(cr, "bluetooth_on") == 1
            "auto_rotate" -> android.provider.Settings.System.getInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION) == 1
            "power_saving" -> svc.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
            "nfc" -> android.nfc.NfcAdapter.getDefaultAdapter(svc)?.isEnabled
            "dark_mode" -> svc.getSystemService(android.app.UiModeManager::class.java).nightMode == android.app.UiModeManager.MODE_NIGHT_YES
            else -> null
        }
    }.getOrNull()

    /**
     * 퀵 설정 타일 토글 — 일반 앱은 블루투스 등을 직접 못 켠다(안드로이드 13+). 타일은 「설명=블루투스, checkable」이고
     * 같은 이름이 둘(타일 전체·아이콘) 잡히는데, 전체를 누르면 상세 패널이 열린다 — 작은 쪽(아이콘)을 누른다(2026-09-26 트리 실측).
     * 블루투스를 켜면 삼성이 기기 검색 창을 띄워 패널을 가린다 — 판정은 시스템 값으로 하고, 그 창은 닫는다.
     */
    private fun quickToggle(tile: String, on: Boolean): String {
        val label = TILES[tile] ?: return "실패: 허용하지 않는 타일 $tile"
        fun word(b: Boolean) = if (b) "켜짐" else "꺼짐"
        if (systemState(tile) == on) return "$label 이미 ${word(on)}"
        // 이름은 상태에 따라 바뀐다: 자동 회전 켜짐 「자동 회전, 세로 고정」 · 꺼짐 「세로, 자동 회전」 → 앞부분이 아니라 포함으로 찾는다
        fun find() = findAll { it.isCheckable && it.contentDescription?.toString()?.contains(label) == true }.minByOrNull { area(it) }
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        Thread.sleep(1200)
        var result: String
        try {
            val t = find() ?: return "실패: 퀵 설정에 「$label」 타일이 안 보임(다른 페이지에 있을 수 있음)"
            if (!t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                val r = Rect(); t.getBoundsInScreen(r); svc.tapAt(r.exactCenterX(), r.exactCenterY())
            }
            result = "실패: 「$label」을 눌렀지만 4초 안에 ${word(on)}으로 안 바뀜"
            for (k in 0 until 8) {   // 블루투스는 켜지는 데 1~2초
                Thread.sleep(500)
                if ((systemState(tile) ?: find()?.isChecked) == on) { result = "$label ${if (on) "켬" else "끔"}(시스템 상태로 확인)"; break }
            }
        } finally {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            Thread.sleep(500)
        }
        // 블루투스 검색 창(설정 앱의 BluetoothScanDialog)이 떴으면 닫는다 — 사용자가 연결까지 원했으면 모델이 다시 연다
        if (tile == "bluetooth" && svc.rootInActiveWindow?.packageName == "com.android.settings" &&
            findAll { it.text?.toString() == "연결 가능한 기기" }.isNotEmpty()) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(400)
            result += " · 자동으로 뜬 기기 검색 창은 닫음"
        }
        return result
    }

    /**
     * 스톱워치 — 시계 앱의 시작 인텐트·브로드캐스트는 다른 앱에서 안 먹었다(비공개·무반응, 2026-09-26) → 화면 조작을 고정 절차로.
     * 상태는 버튼 id 로 가른다(접근성 트리 실측 — 도는 중엔 화면이 계속 바뀌어 uiautomator 는 못 뜬다):
     *   대기 startButton「시작」+lapButton(비활성) · 도는 중 stopButton「중지」+lapButton「구간 기록」 · 멈춤 resumeButton「계속」+resetButton「초기화」
     */
    private fun stopwatch(action: String): String {
        val pkg = "com.sec.android.app.clockpackage"
        fun byId(id: String) = findAll { it.viewIdResourceName == "stopwatch_$id" && it.packageName == pkg }.firstOrNull()
        fun state() = when {
            byId("stopButton") != null -> "running"
            byId("resumeButton") != null -> "paused"
            byId("startButton") != null -> "idle"
            else -> null
        }
        // 경과는 「3.70초」「1분 3.20초」 같은 설명으로 나온다
        fun elapsed() = findAll { it.packageName == pkg && it.contentDescription?.toString()?.matches(Regex("[0-9.시간분초 ]*초")) == true }
            .firstOrNull()?.contentDescription?.toString() ?: "?"
        fun word(st: String?) = when (st) { "running" -> "도는 중"; "paused" -> "멈춤"; "idle" -> "0초(대기)"; else -> "?" }
        fun press(id: String): Boolean { val ok = byId(id)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true; Thread.sleep(700); return ok }

        if (state() == null) {
            val launch = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return "실패: 시계 앱 없음"
            svc.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(1200)
            if (state() == null) {
                val tab = findAll { it.contentDescription?.toString() == "스톱워치" && it.packageName == pkg }.firstOrNull()
                    ?: return "실패: 시계 앱에서 스톱워치 탭을 못 찾음"
                var c: AccessibilityNodeInfo? = tab
                while (c != null && !c.isClickable) c = c.parent
                (c ?: tab).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(1000)
            }
        }
        val before = state() ?: return "실패: 스톱워치 화면이 안 열림"
        when (action) {
            "get" -> return "스톱워치 ${word(before)} · 경과 ${elapsed()}"
            "start" -> when (before) { "running" -> return "스톱워치 이미 도는 중 · 경과 ${elapsed()}"; "paused" -> press("resumeButton"); else -> press("startButton") }
            "stop" -> if (before == "running") press("stopButton") else return "스톱워치 이미 ${word(before)} · 경과 ${elapsed()}"
            "lap" -> if (before == "running") press("lapButton") else return "구간 기록은 도는 중에만 된다(지금 ${word(before)})"
            "reset" -> when (before) {
                "idle" -> return "스톱워치 이미 0초"
                "running" -> { press("stopButton"); press("resetButton") }
                else -> press("resetButton")
            }
            else -> return "실패: 알 수 없는 동작 $action"
        }
        val after = state()
        return "스톱워치 $action: ${word(before)} → ${word(after)} · 경과 ${elapsed()}"
    }

    /** 손가락 방향 밀기. 항목을 주면 그 항목의 폭·높이 안에서, 가장자리면 화면 끝에서 시작한다 */
    private fun swipeTool(dir: String, index: Int, fromEdge: Boolean): String {
        val dm = svc.resources.displayMetrics
        val r = if (index >= 0) Rect().also { node(index).getBoundsInScreen(it) } else Rect(0, 0, dm.widthPixels, dm.heightPixels)
        val cx = r.exactCenterX(); val cy = r.exactCenterY()
        val (x1, y1, x2, y2) = when (dir) {
            "left" -> listOf(if (fromEdge) dm.widthPixels - 2f else r.left + r.width() * 0.8f, cy, r.left + r.width() * 0.2f, cy)
            "right" -> listOf(if (fromEdge) 2f else r.left + r.width() * 0.2f, cy, r.left + r.width() * 0.8f, cy)
            "up" -> listOf(cx, if (fromEdge) dm.heightPixels - 2f else r.top + r.height() * 0.8f, cx, r.top + r.height() * 0.2f)
            "down" -> listOf(cx, if (fromEdge) 2f else r.top + r.height() * 0.2f, cx, r.top + r.height() * 0.8f)
            else -> return "실패: 알 수 없는 방향 $dir"
        }
        val what = if (index >= 0) "[$index] ${ScreenReader.labelOf(node(index)).take(20)} " else ""
        return if (svc.swipe(x1, y1, x2, y2)) "밀기 $dir: $what".trim() else "밀기 실패"
    }

    private fun openScreen(a: JSONObject): String {
        val name = a.optString("name")
        val spec = SCREENS[name] ?: return "없는 화면: $name — open_screen 목록에 없으면 open_app 후 화면 조작"
        val i = Intent(spec.substringBefore('|')).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if ('|' in spec) android.content.ComponentName.unflattenFromString(spec.substringAfter('|'))?.let { i.component = it }
        if (name == "new_event") {
            i.data = android.provider.CalendarContract.Events.CONTENT_URI
            // 캘린더 앱이 둘(삼성·구글)이라 선택창이 떴고 모델이 「이번만」을 눌러 넘어갔다(2026-09-26) — 삼성 캘린더로 고정
            if (svc.packageManager.resolveActivity(Intent(i).setPackage("com.samsung.android.calendar"), 0) != null) i.setPackage("com.samsung.android.calendar")
            a.optString("title").takeIf { it.isNotBlank() }?.let { i.putExtra(android.provider.CalendarContract.Events.TITLE, it) }
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA)
            val begin = a.optString("begin").takeIf { it.isNotBlank() }?.let {
                runCatching { fmt.parse(it)!!.time }.getOrElse { return "begin 형식이 틀림(yyyy-MM-dd HH:mm): $it" }
            }
            if (begin != null) {
                val end = a.optString("end").takeIf { it.isNotBlank() }?.let { runCatching { fmt.parse(it)!!.time }.getOrNull() } ?: (begin + 3600_000)
                i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end)
            }
            if (a.optBoolean("all_day")) i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
        return try {
            svc.startActivity(i); Thread.sleep(800)
            "화면 열기: $name" + if (name == "new_event") " — 내용이 채워졌는지 화면으로 확인하고, 저장은 irreversible=true 로 사용자 확인을 받는다" else ""
        } catch (e: android.content.ActivityNotFoundException) {
            "이 폰에서 열 수 없는 화면: $name"
        }
    }

    private var appsCache:List<Pair<android.content.pm.ResolveInfo, String>>? = null
    private fun launcherApps() = appsCache ?: svc.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
    ).map { it to it.loadLabel(svc.packageManager).toString() }.also { appsCache = it }

    private fun openApp(name: String): String {
        val q = Knowledge.norm(name)
        if (q.isEmpty()) return "앱 이름이 비었음"
        val apps = launcherApps()
        val alias = kb.aliasPkg(name)
        val hit = apps.firstOrNull { it.first.activityInfo.packageName == (alias ?: name.trim()) }
            ?: apps.firstOrNull { Knowledge.norm(it.second) == q }
            ?: apps.filter { Knowledge.norm(it.second).contains(q) }.minByOrNull { it.second.length }
            ?: apps.firstOrNull { it.first.activityInfo.packageName.contains(q) }
        if (hit == null) {
            failedAppNames += name
            return "앱 '$name' 을 못 찾음 — 「설치된 앱」 목록의 표기로 다시 부르거나 list_apps 로 확인할 것"
        }
        val ai = hit.first.activityInfo
        svc.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(ai.packageName, ai.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
        Thread.sleep(600)
        return "앱 열기: ${hit.second} (${ai.packageName})"
    }

    /** 패키지명 그대로이거나 설치된 앱 이름이면 패키지를 돌려준다 */
    private fun resolvePkg(nameOrPkg: String): String? {
        val s = nameOrPkg.trim()
        runCatching { svc.packageManager.getPackageInfo(s, 0); return s }
        val q = Knowledge.norm(s)
        val apps = launcherApps()
        return (kb.aliasPkg(s)?.let { a -> apps.firstOrNull { it.first.activityInfo.packageName == a } }
            ?: apps.firstOrNull { Knowledge.norm(it.second) == q }
            ?: apps.filter { Knowledge.norm(it.second).contains(q) }.minByOrNull { it.second.length })?.first?.activityInfo?.packageName
    }

    private fun listApps(query: String): String {
        val q = Knowledge.norm(query)
        val list = launcherApps().filter {
            q.isEmpty() || Knowledge.norm(it.second).contains(q) || it.first.activityInfo.packageName.contains(q)
        }.sortedBy { it.second }.take(150)
        if (list.isEmpty()) return "'$query' 에 맞는 앱 없음 — 한글/영문 표기를 바꿔 보거나 query 를 비워 전체를 볼 것"
        return list.joinToString("\n") { "${it.second} (${it.first.activityInfo.packageName})" }
    }

    private val SENSITIVE_SCHEMES = setOf("banktoss", "banktoss-live", "supertoss", "intoss", "kakaopay", "samsungpay", "allsamsungpay", "kakaobank", "kbbank", "shinhan")

    /**
     * capture 로 저장한 사진(우리 앱 소유라 권한 없이 읽힌다)을 이름으로 찾아 ACTION_SEND(_MULTIPLE)로 넘긴다.
     * 왜: 갤러리 선택 화면의 썸네일은 설명이 「버튼」뿐이라 모델이 어느 사진을 골랐는지 확인 못 하고 포기했다(2026-09-26 QA-K1 카톡 방 공유).
     * 받는 앱의 공유 화면(카톡 = 방 고르기)에서 멈춘다 — 전송은 화면 조작 + 확인 게이트.
     */
    /** 이 앱이 저장한 캡처 목록(최신순) — share_images·trash_captures 공용. 권한 없이 읽히는 건 우리 소유 파일뿐이다 */
    private fun ownCaptures(): List<Pair<String, android.net.Uri>> {
        val out = ArrayList<Pair<String, android.net.Uri>>()
        svc.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DISPLAY_NAME),
            "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} LIKE 'FoldAgent_%'", null,
            "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && out.size < 500) out += c.getString(1) to android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
        }
        return out
    }

    /**
     * 테스트 캡처 정리(2026-09-26 사용자 요청) — 갤러리 UI 로는 어느 사진인지 못 가려 지울 수 없어서, 우리가 만든 파일만 이름으로 찾아
     * MediaStore IS_TRASHED 로 휴지통에 옮긴다(삭제 아님, 30일 뒤 시스템이 비움). 개수와 이름을 확인 게이트로 묻는다.
     */
    /**
     * system_setting — 허용 목록 설정 읽기·쓰기(SysSettings). 쓰기는 세 키만, 전부 확인 게이트 뒤에.
     * gate() 는 「「X」 … 할까요?」 모양이라 설정 문장이 어색해 svc.confirm 을 직접 부르고 trace 「confirm」 은 같은 모양으로 남긴다.
     * 모델이 게이트를 건너뛸 인자는 두지 않는다. 쓴 뒤 다시 읽어 확인한 값만 보고한다.
     */
    private fun systemSetting(action: String, key: String, value: String): String {
        if (action == "get") {
            if (key.isBlank()) return "설정값(허용 목록):\n" + SysSettings.KEYS.keys.joinToString("\n") { "- " + SysSettings.describe(svc, it) }
            if (key !in SysSettings.KEYS) return "실패: 허용 목록에 없는 키 $key — 허용: ${SysSettings.KEYS.keys.joinToString()}"
            return SysSettings.describe(svc, key)
        }
        if (action != "set") return "실패: 알 수 없는 동작 $action (get | set)"
        val spec = SysSettings.KEYS[key] ?: return "실패: 허용 목록에 없는 키 $key — 바꿀 수 있는 것: ${SysSettings.KEYS.filterValues { it.writable }.keys.joinToString()}"
        if (!spec.writable) return "실패: ${spec.label}($key)는 읽기만 허용된다" + spec.readOnlyWhy.let { if (it.isBlank()) " — 바꾸려면 open_settings 로 화면을 열어 사용자에게 안내" else " — $it" }
        val (target, note) = SysSettings.parse(key, value)
            ?: return "실패: 값 '$value' 을 이해 못함 — " + if (key == "screen_off_timeout") "「5분」「30초」「최대」처럼" else "on/off 로"
        val before = SysSettings.rawOrNull(svc, key)
        val same = before?.toIntOrNull()?.let { if (key == "stay_on_while_plugged_in") (it != 0) == (target != 0) else it == target } == true
        if (same) return "이미 ${spec.label} ${spec.show(before!!)} — 바꾸지 않음" + (note?.let { " ($it)" } ?: "")   // 바꿀 게 없으면 묻지 않는다
        if (!SysSettings.canWrite(svc, spec)) {
            val perm = if (spec.perm == SysSettings.Perm.SECURE) "WRITE_SECURE_SETTINGS" else "WRITE_SETTINGS"
            return "실패: 권한 없음($perm) — 맥에서 ./dev.sh grant 가 필요하다고 사용자에게 말하고 finish(success=false). 화면에서 찾지 말 것" +
                if (key == "stay_on_while_plugged_in") "(이 폰 개발자 옵션 목록엔 안 보인다)" else if (key == "accelerometer_rotation") " — 자동 회전은 quick_toggle(auto_rotate) 로 대신할 수 있다" else ""
        }
        // 거부된 키는 이번 run 에서 다시 묻지 않는다 — 프롬프트 지시만으론 get→set 왕복마다 게이트·음성이 다시 떠 40초씩 막힐 수 있었다
        // (기기 도구 분기는 반복·왕복 감시를 안 탄다). 두 번째 재시도면 멈춘다
        if (key in declinedSettings) {
            declinedRetries++
            trace.event("guard", JSONObject().put("kind", "declined_setting").put("key", key).put("count", declinedRetries))
            if (declinedRetries >= 2) oscillating = true
            return "실패: 이미 사용자가 거부함 — 다시 묻지 않았다. 바꾸지 말고 finish 할 것"
        }
        val q = SysSettings.question(key, before, target, note)
        val ok = svc.confirm(q)                                    // 40초 무응답 = false
        trace.event("confirm", JSONObject().put("label", spec.label).put("what", "설정 변경 $key ${before ?: "?"}→$target").put("ok", ok))
        if (!ok) { declinedSettings += key; return "실패: 사용자가 거부함(또는 응답 없음) — 바꾸지 않았다. 다시 묻지 말고 finish 할 것" }
        val wrote = try { SysSettings.put(svc, key, target) } catch (e: Exception) { return "실패: 시스템이 막음(${e.message?.take(80)}) — 화면에서 찾지 말 것" }
        val after = SysSettings.rawOrNull(svc, key)                // 다시 읽어 확인한 값만 보고
        trace.event("sys_setting", JSONObject().put("key", key).put("value", value).put("before", before ?: JSONObject.NULL)
            .put("target", target).put("after", after ?: JSONObject.NULL).put("wrote", wrote))
        if (after?.toIntOrNull() != target) return "실패: 썼지만 다시 읽은 값이 ${after ?: "없음"} — 적용 안 됨"
        val plug = if (key == "stay_on_while_plugged_in" && target != 0 && !SysSettings.pluggedNow(svc)) " · 지금은 충전 중이 아니라 충전기를 꽂으면 적용된다" else ""
        return "${spec.label} ${before?.let(spec.show) ?: "?"} → ${spec.show(after)}" + (note?.let { " ($it)" } ?: "") + plug +
            ". 되돌리기: ${SysSettings.undo(key, before, target)}"
    }

    /**
     * 홈 비서 도구 한 번 — 홈맥 /api/phone/exec 를 부른다(계약 §0·§3-1·§3-2). 화면을 안 읽고 안 바꾼다.
     *   op 조립 → (쓰기면) 이번 run 거부 대상인지 → 확인 게이트(svc.confirm, 40초 무응답 = 거부) → confirmId(UUID)
     *   → 상태 문구 → client.exec(정지하면 끊긴다) → 결과 문자열 규약 → steps·trace「assistant」·emit·failStreak
     * 게이트는 도구 종류(GATED)만으로 무조건 건다 — 모델이 건너뛸 인자는 없다. 토큰은 trace·결과 어디에도 넣지 않는다.
     */
    private fun assistantTool(step: Int, name: String, a: JSONObject): String {
        val t0 = SystemClock.uptimeMillis()
        val gated = name in AssistantTools.GATED
        var op = "?"
        var sent: JSONObject? = null
        var confirmId: String? = null
        var reply: AssistantClient.Reply? = null
        var cancelledOut = false
        var outcome: String = run {
            val token = Prefs.assistantToken(svc)
            if (token.isBlank() || Prefs.assistantBase(svc).isBlank()) return@run "실패: 홈 비서 연결이 설정되지 않았다(폰 설정 › 홈 비서) — finish 로 알릴 것"
            val (o, args) = try { AssistantTools.op(name, a) } catch (e: IllegalArgumentException) { return@run "실패: 인자 오류 — ${e.message}" }
            op = o
            if (name == "memory_add") args.put("runId", runId)   // 서버가 source=foldagent:<runId> 로 남긴다
            sent = args
            val client = AssistantClient(Prefs.assistantBase(svc), token).also { assistantClient = it }
            try {
                if (gated) {
                    // 거부된 대상은 이번 run 에서 다시 묻지 않는다 — system_setting 과 같은 집합·카운터(키 = 도구명:대상이라 설정 키와 안 겹친다)
                    // 키·확인 창은 서버로 갈 정규화 인자(args)로 만든다 — 보여 준 것 = 보내는 것(주소 표기·시각 오프셋이 달라도 같은 대상)
                    val key = AssistantTools.gateKey(name, args)
                    if (key in declinedSettings) {
                        declinedRetries++
                        trace.event("guard", JSONObject().put("kind", "declined_assistant").put("key", key.take(80)).put("count", declinedRetries))
                        if (declinedRetries >= 2) oscillating = true
                        return@run "실패: 이미 사용자가 거부함 — 다시 묻지 않았다. 실행하지 말고 finish 할 것"
                    }
                    // 확인 창에 넣을 맥락: 답장은 원문(보낸 사람·제목)을 먼저 읽고, 삭제는 이번 실행에서 agenda 로 본 일정
                    val ctx: JSONObject? = when (name) {
                        "mail_reply" -> {
                            svc.status(AssistantTools.status("mail_read"))
                            val r = client.exec("mail_read", JSONObject().put("id", a.optString("id").trim()), null,
                                ASSISTANT_TIMEOUT_MS["mail_read"] ?: 40_000) { cancelled }
                            if (!r.ok) { reply = r; if (cancelled) { cancelledOut = true; return@run "사용자가 멈춤" }
                                return@run "실패: 답장할 원문을 못 읽음 — " + failText(r) }
                            r.data
                        }
                        "event_delete" -> {
                            // 이번 실행에서 agenda 로 본 일정만 지운다 — 새 턴에 세션 기록의 id 로 바로 부르면 확인 창에 제목·시각이 빠졌다(계약 §0-2, 리뷰 runtime#2)
                            val ev = seenEvents[args.optString("event_id").trim()]
                                ?: return@run "실패: 이번 실행에서 조회하지 않은 일정이라 확인 창에 제목·시각을 보여 줄 수 없다 — 먼저 agenda 로 그 일정을 찾은 뒤 그 줄의 id=·cal= 로 다시 부를 것"
                            // 확인 창에 보이는 일정의 캘린더로 보낸다 — 보여 준 것 = 보내는 것(리뷰 safety#8)
                            ev.optString("calendarId").trim().takeIf { it.isNotEmpty() && it != "null" }?.let { args.put("calendar_id", it) }
                            ev
                        }
                        else -> null
                    }
                    if (cancelled) { cancelledOut = true; return@run "사용자가 멈춤" }
                    val ok = svc.confirm(AssistantTools.gateText(name, args, ctx))   // 40초 무응답 = false
                    trace.event("confirm", JSONObject().put("label", name).put("what", "홈 비서 $op").put("ok", ok))
                    if (cancelled) { cancelledOut = true; return@run "사용자가 멈춤" }
                    if (!ok) { declinedSettings += key; return@run "실패: 사용자가 거부함(또는 응답 없음) — 실행하지 않았다. 다시 묻지 말고 finish 할 것" }
                    confirmId = assistantConfirmIds.getOrPut(key) { java.util.UUID.randomUUID().toString() }
                }
                svc.status(AssistantTools.status(name))
                // 답장은 원문 읽기에 이미 한 번 보냈다 — bodySent 가 그 요청으로 켜져 있으면 안 되니 본 요청은 새 클라이언트로
                val main = if (name == "mail_reply") AssistantClient(Prefs.assistantBase(svc), token).also { assistantClient = it } else client
                val r = main.exec(op, args, confirmId, ASSISTANT_TIMEOUT_MS[op] ?: 40_000) { cancelled }
                reply = r
                // 쓰기 요청이 홈맥에 닿은 뒤 끊김·시간초과로 끝났으면 이미 실행됐을 수 있다 — 「안 됨」으로 알리면 다시 보내 이중 발송이 된다(리뷰 safety#3·runtime#4)
                val unknown = gated && !r.ok && main.bodySent && uncertain(r)
                when {
                    !r.ok && cancelled -> {
                        cancelledOut = true
                        if (unknown) "사용자가 멈춤 — 단 요청이 이미 홈맥에 닿아 실행됐을 수 있다(결과 불명). 다시 보내기 전에 확인할 것" else "사용자가 멈춤"
                    }
                    unknown -> "실패: 결과 불명 — ${failText(r)}. 요청이 홈맥에 닿아 이미 실행됐을 수 있다. " +
                        "같은 요청을 다시 보내지 말고 finish 로 사용자에게 확인을 부탁할 것(메일은 mail_list, 일정은 agenda 로 확인할 수 있다)"
                    // 사용자가 멈추지 않았는데 서버가 끊김(499)을 돌려준 경우 — 「멈춤」으로 보고하면 모델이 사용자가 멈춘 줄 안다(리뷰 runtime#11)
                    r.code == "cancelled" -> "실패: 홈맥 쪽에서 요청이 끊김${r.message?.let { " ($it)" } ?: ""}"
                    !r.ok -> "실패: " + failText(r)
                    else -> {
                        val data = r.data ?: JSONObject()
                        if (name == "agenda") data.optJSONArray("events")?.let { ev ->
                            for (i in 0 until ev.length()) ev.optJSONObject(i)?.let { e -> e.optString("id").takeIf { it.isNotBlank() }?.let { seenEvents[it] = e } }
                        }
                        // 쓰기 도구 format 은 ✅ 줄에 받는 사람·제목을 쓰려고 요청 인자가 필요하다 → data 사본에 request 로 넣어 준다(실제로 보낸 정규화 인자)
                        val shown = if (gated || name == "agenda") JSONObject(data.toString()).put("request", args) else data   // agenda 도 조회 기간을 머리에 밝히려고
                        runCatching { AssistantTools.format(name, shown) }.getOrElse { "결과를 읽지 못함: ${it.message}" } +
                            if (gated && data.optBoolean("replay", false)) "\n(같은 확인으로 이미 실행된 요청 — 홈맥이 다시 실행하지 않고 첫 결과를 돌려줬다)" else ""
                    }
                }
            } finally {
                assistantClient = null
            }
        }
        // 조회형 비서 도구는 화면 감시(repeatKey·actionHistory)를 안 타 같은 조회를 되풀이해도 HARD_CAP 까지 갔다(리뷰 runtime#6)
        if (name in AssistantTools.READ_ONLY && !cancelledOut && !outcome.startsWith("실패")) {
            val rk = "$name ${sent ?: a}"
            assistantCalls += rk
            val n = assistantCalls.takeLast(10).count { it == rk }
            if (n >= 3) {
                lastGuard = "$name 을 같은 인자로 최근 ${n}번 조회"
                outcome += "\n⚠️ 같은 조회를 최근 ${n}번 했다 — 결과는 위와 같다. 다시 부르지 말고 그 결과로 답하거나 finish 할 것."
                trace.event("guard", JSONObject().put("step", step).put("key", rk.take(80)).put("count", n).put("kind", "assistant_repeat"))
            }
            if (n >= 5) oscillating = true
        }
        // 실패만 센다 — 정지는 사용자가 한 것이라 안 센다(계약 §0-8)
        if (!cancelledOut) failStreak = if (outcome.startsWith("실패")) failStreak + 1 else 0
        steps.put(JSONObject().put("tool", name).put("summary", AssistantTools.summarize(name, a, outcome)))
        val traceArgs = JSONObject(a.toString()).also { t -> if (t.has("body")) t.put("body", t.optString("body").take(80)) }
        trace.event("assistant", JSONObject().put("step", step).put("tool", name).put("op", op).put("ms", SystemClock.uptimeMillis() - t0)
            .put("ok", reply?.ok == true && !outcome.startsWith("실패")).put("code", reply?.code ?: JSONObject.NULL).put("gated", gated)
            .put("confirmId", confirmId ?: JSONObject.NULL).put("args", traceArgs).put("sent_keys", sent?.keys()?.asSequence()?.toList()?.let { JSONArray(it) } ?: JSONObject.NULL))
        svc.emit("step", "$name: ${outcome.lineSequence().first().take(70)}")
        return outcome
    }

    /**
     * 직접 도구 한 번(web_search·read_url·image_gen) — 계약 cc-capabilities §1-1. 실행은 OaiTools.exec(W-P1)이,
     * 여기는 상태 문구·failStreak·steps·trace「oai」·emit 만. 정지는 exec 가 cancelled 를 보고 연결을 끊는다. 첨부는 addAttachment 로 모인다.
     */
    private fun oaiTool(step: Int, name: String, a: JSONObject): String {
        val t0 = SystemClock.uptimeMillis()
        svc.status(OaiTools.status(name))
        val outcome = try {
            OaiTools.exec(svc, name, a, { cancelled }) { att -> addAttachment(att) }
        } catch (e: Exception) {
            if (cancelled) "사용자가 멈춤" else "실패: ${e.javaClass.simpleName}${e.message?.let { " — ${it.take(160)}" } ?: ""}"
        }
        val cancelledOut = outcome.startsWith("사용자가 멈춤")
        // 실패만 센다 — 정지는 사용자가 한 것이라 안 센다
        if (!cancelledOut) failStreak = if (outcome.startsWith("실패")) failStreak + 1 else 0
        steps.put(JSONObject().put("tool", name).put("summary", OaiTools.summarize(name, a, outcome)))
        val traceArgs = JSONObject(a.toString()).also { t -> if (t.has("prompt")) t.put("prompt", t.optString("prompt").take(80)) }
        trace.event("oai", JSONObject().put("step", step).put("tool", name).put("ms", SystemClock.uptimeMillis() - t0)
            .put("ok", !outcome.startsWith("실패") && !cancelledOut).put("args", traceArgs).put("outcome", outcome.take(300)))
        svc.emit("step", "$name: ${outcome.lineSequence().first().take(70)}")
        return outcome
    }

    /**
     * 스킬·실행·MCP 도구 한 번 — 홈맥 /api/phone/exec 새 op(계약 cc-capabilities §1-2). 화면을 안 읽고 안 바꾼다.
     * assistantTool 과 같은 틀이지만 **승인 판정은 서버가 한다**(§0-1): 폰은 게이트 목록을 갖지 않는다.
     *   첫 호출 → 서버가 auto 면 그대로 결과
     *            → need_confirm 이면 data.confirm 의 서버 문구를 그대로 svc.confirm(40초 무응답 = 거부)
     *              → 승인: 같은 args + confirmId(UUID, 거부 키마다 하나) + confirmToken 재전송 / 거부: declined(키 = 도구:대상:토큰 앞 16자)
     *            → not_allowed(deny) 면 「차단된 동작 — 다시 시도하지 말 것」
     *   run 결과가 job(background·promoted)이면 「맡김」 + Jobs.register(W-P3) — 모델은 기다리지 않고 finish.
     *   동기 결과의 서버 파일은 CapTools.attachments(W-P2)가 받아 오고 addAttachment 로 모인다.
     * 실행 op(CapTools.EXEC)가 홈맥에 닿은 뒤 끊기면 「결과 불명 — 다시 보내지 말 것」(두 번 돌지 않게). 토큰은 trace·결과 어디에도 없다.
     */
    private fun capTool(step: Int, name: String, a: JSONObject): String {
        val t0 = SystemClock.uptimeMillis()
        var op = "?"
        var sent: JSONObject? = null
        var confirmId: String? = null
        var confirmClass: String? = null
        var reply: AssistantClient.Reply? = null
        var jobId: String? = null
        var cancelledOut = false
        val executes = name in CapTools.EXEC
        var outcome: String = run {
            val token = Prefs.assistantToken(svc)
            if (token.isBlank() || Prefs.assistantBase(svc).isBlank()) return@run "실패: 홈 비서 연결이 설정되지 않았다(폰 설정 › 홈 비서) — finish 로 알릴 것"
            val (o, args) = try { CapTools.op(name, a) } catch (e: IllegalArgumentException) { return@run "실패: 인자 오류 — ${e.message}" }
            op = o
            // job 이 결과를 맡긴 방에 붙이도록(계약 §2) — run 만. 첫 요청부터 넣어 confirmToken 이 이것까지 덮는다(재전송도 같은 값)
            if (name == "run") args.put("sessionId", session.id).put("runId", runId)
                .put("title", task.replace(Regex("\\s+"), " ").trim().take(60))
            // claude_task 는 늘 job — 결과를 붙일 방·실행을 같이 보낸다(DELEGATION §3-1). title 은 모델이 준 것만(없으면 서버가 task 앞 30자)
            if (name == "claude_task") args.put("sessionId", session.id).put("runId", runId)
            sent = args
            val base = Prefs.assistantBase(svc)
            val timeout = if (name == "run" && args.optBoolean("background")) CAP_BACKGROUND_TIMEOUT_MS else CAP_TIMEOUT_MS[op] ?: 40_000
            try {
                svc.status(CapTools.status(name))
                var client = AssistantClient(base, token).also { assistantClient = it }
                var r = client.exec(op, args, null, timeout) { cancelled }
                if (!r.ok && r.code == "need_confirm" && !cancelled) {
                    val d = r.data ?: JSONObject()
                    val conf = d.optJSONObject("confirm") ?: JSONObject()
                    val tok = d.optString("confirmToken").trim().takeIf { it.isNotEmpty() && it != "null" }
                    confirmClass = conf.optString("class").ifBlank { "confirm" }
                    val text = CapTools.confirmText(name, args, conf)
                    // 보여 줄 서버 문구나 토큰이 없으면 묻지 않는다 — 무엇이 실행될지 모르는 채로 승인받지 않는다(§0-2)
                    if (tok == null || text.isBlank())
                        return@run "실패: 홈맥이 확인을 요구했지만 확인 문구·토큰이 없어 묻지 않았다(실행 안 함) — 다시 시도하지 말고 finish 로 알릴 것"
                    val key = "$name:${CapTools.gateTarget(name, args)}:${tok.take(16)}"
                    if (key in declinedSettings) {
                        declinedRetries++
                        trace.event("guard", JSONObject().put("kind", "declined_cap").put("key", key.take(80)).put("count", declinedRetries))
                        if (declinedRetries >= 2) oscillating = true
                        return@run "실패: 이미 사용자가 거부함 — 다시 묻지 않았다. 실행하지 말고 finish 할 것"
                    }
                    val ok = svc.confirm(text)   // 40초 무응답 = false
                    trace.event("confirm", JSONObject().put("label", name).put("what", "홈맥 $op ($confirmClass)").put("ok", ok))
                    if (cancelled) { cancelledOut = true; return@run "사용자가 멈춤" }
                    if (!ok) { declinedSettings += key; return@run "실패: 사용자가 거부함(또는 응답 없음) — 실행하지 않았다. 다시 묻지 말고 finish 할 것" }
                    confirmId = assistantConfirmIds.getOrPut(key) { java.util.UUID.randomUUID().toString() }
                    svc.status(CapTools.status(name))
                    // 새 클라이언트 — bodySent 를 이 요청으로만 재야 「결과 불명」 판정이 맞다
                    client = AssistantClient(base, token).also { assistantClient = it }
                    r = client.exec(op, args, confirmId, timeout, tok) { cancelled }
                }
                reply = r
                val unknown = executes && !r.ok && client.bodySent && uncertain(r)
                when {
                    !r.ok && cancelled -> {
                        cancelledOut = true
                        if (unknown) "사용자가 멈춤 — 단 요청이 이미 홈맥에 닿아 실행됐을 수 있다(결과 불명). 다시 보내기 전에 job_status 로 확인할 것" else "사용자가 멈춤"
                    }
                    unknown -> "실패: 결과 불명 — ${capFailText(r)}. 요청이 홈맥에 닿아 이미 실행(또는 작업 등록)됐을 수 있다. " +
                        "같은 요청을 다시 보내지 말고 finish 로 사용자에게 알릴 것(맡긴 작업은 job_status 로 볼 수 있다)"
                    r.code == "cancelled" -> "실패: 홈맥 쪽에서 요청이 끊김${r.message?.let { " ($it)" } ?: ""}"
                    // 승인 뒤 재전송에도 need_confirm — 서버가 본 요청이 달라졌다. 다시 묻지 않는다(무한 확인 방지)
                    r.code == "need_confirm" -> "실패: 승인 뒤에도 홈맥이 다시 확인을 요구함 — 실행 안 됨. 다시 시도하지 말고 finish 로 알릴 것"
                    !r.ok -> "실패: " + capFailText(r)
                    else -> {
                        val data = r.data ?: JSONObject()
                        val jid = data.optString("jobId").trim().takeIf { it.isNotEmpty() && it != "null" }
                        val promoted = data.optBoolean("promoted", false)
                        // 정책 long:true 로 서버가 만든 job 은 {mode:"job", jobId, promoted:false} 로 오고 state 가 없다 — mode 도 본다(CapTools.format 의 「맡김」 판정과 같게)
                        val handedOff = (name == "claude_task" && jid != null) || name == "run" && jid != null &&
                            (promoted || args.optBoolean("background") || data.optString("mode") == "job" ||
                                data.optString("state") in setOf("queued", "running"))
                        if (handedOff) {
                            jobId = jid
                            // claude_task 는 일 전체를 넘긴다 — 결과가 뒤에 오니 이번 턴의 못 맞춘 조건은 실패가 아니다(실기 cc-phone-2 failed 기록)
                            // 맡긴 턴(run 뒤로·claude_task)은 결과가 나중에 온다 — 넘긴 조건을 못 채웠다고 실패로 치지 않는다(실기 2026-09-28 01:53: 모델이 success=false 로 끝냄)
                            handedOffThen = true
                            val jobTitle = args.optString("title").ifBlank { if (name == "claude_task") args.optString("task").replace(Regex("\\s+"), " ").take(60) else "" }
                            val jobSkill = if (name == "claude_task") "claude" else args.optString("skill")
                            // 사슬 깊이(계약 INPUTS §5): 사람이 시킨 실행 = 1, 자동 이어가기 실행(meta chainDepth = 앞 job 깊이) = +1
                            val depth = meta.optInt("chainDepth", 0).coerceAtLeast(0) + 1
                            runCatching { Jobs.register(svc, jid!!, session.id, runId, jobTitle, name, jobSkill, a.optString("then").trim(), depth) }
                                .onFailure { trace.event("error", JSONObject().put("where", "jobs.register").put("message", it.toString())) }
                            if (name == "claude_task") CapTools.format(name, data)
                            else "맡김 — 작업 $jid, 끝나면 알림" + (if (promoted) " (90초를 넘겨 홈맥이 뒤로 돌렸다)" else "") +
                                "\n기다리거나 job_status 를 되풀이하지 말고 finish 로 사용자에게 「홈맥에 맡겼고 끝나면 알림이 온다」고 알릴 것"
                        } else {
                            val atts = if (cancelled) emptyList() else runCatching { CapTools.attachments(svc, base, token, name, data) { cancelled } }
                                .getOrElse { trace.event("error", JSONObject().put("where", "cap.attachments").put("message", it.toString())); emptyList() }
                            val added = atts.filter { addAttachment(it) }
                            // 그림은 갤러리 이름을 알려 준다 — 「그 그래프 보내 줘」 때 share_images 에 넘길 이름(모델은 uri 를 못 본다)
                            val imgs = added.filter { it.optString("kind") == "image" }.map { it.optString("title") }
                            runCatching { CapTools.format(name, data) }.getOrElse { "결과를 읽지 못함: ${it.message}" } +
                                (if (added.isNotEmpty()) "\n(첨부 ${added.size}개를 채팅에 붙였다" +
                                    (if (imgs.isNotEmpty()) " — 갤러리 이미지: ${imgs.joinToString(", ")}" else "") + ")" else "") +
                                (if (data.optBoolean("replay", false)) "\n(같은 확인으로 이미 실행된 요청 — 홈맥이 다시 실행하지 않고 첫 결과를 돌려줬다)" else "")
                        }
                    }
                }
            } finally {
                assistantClient = null
            }
        }
        // 조회형은 화면 감시를 안 타 같은 조회를 되풀이해도 안 잡힌다 — 비서 조회와 같은 창으로 센다
        if (name in CapTools.READ_ONLY && !cancelledOut && !outcome.startsWith("실패")) {
            val rk = "$name ${sent ?: a}"
            assistantCalls += rk
            val n = assistantCalls.takeLast(10).count { it == rk }
            if (n >= 3) {
                lastGuard = "$name 을 같은 인자로 최근 ${n}번 조회"
                outcome += "\n⚠️ 같은 조회를 최근 ${n}번 했다 — 결과는 위와 같다. 다시 부르지 말고 그 결과로 답하거나 finish 할 것."
                trace.event("guard", JSONObject().put("step", step).put("key", rk.take(80)).put("count", n).put("kind", "cap_repeat"))
            }
            if (n >= 5) oscillating = true
        }
        if (!cancelledOut) failStreak = if (outcome.startsWith("실패")) failStreak + 1 else 0
        steps.put(JSONObject().put("tool", name).put("summary", CapTools.summarize(name, a, outcome)))
        // 코드·stdin 은 80자만. argv 는 그대로(명령 원문 — 키는 argv 가 아니라 서버가 env 로 넣는다)
        val traceArgs = JSONObject(a.toString()).also { t -> listOf("code", "stdin").forEach { k -> if (t.has(k)) t.put(k, t.optString(k).take(80)) } }
        trace.event("cap", JSONObject().put("step", step).put("tool", name).put("op", op).put("ms", SystemClock.uptimeMillis() - t0)
            .put("ok", reply?.ok == true && !outcome.startsWith("실패")).put("code", reply?.code ?: JSONObject.NULL)
            .put("class", confirmClass ?: JSONObject.NULL).put("confirmId", confirmId ?: JSONObject.NULL).put("jobId", jobId ?: JSONObject.NULL)
            .put("args", traceArgs))
        svc.emit("step", "$name: ${outcome.lineSequence().first().take(70)}")
        return outcome
    }

    /** 스킬·실행 서버 실패 → 모델이 읽을 사유. deny 는 시험 제한이 아니라 정책 차단이다(계약 cc-capabilities §1-2) */
    private fun capFailText(r: AssistantClient.Reply): String = when (r.code) {
        "not_allowed" -> (r.message?.takeIf { it.isNotBlank() }?.let { if ("차단" in it) it else "차단된 동작: $it" } ?: "차단된 동작") +
            " — 다시 시도하지 말고 finish 할 것"
        "bad_op" -> "홈맥이 아직 이 기능을 모른다(${r.message?.take(80) ?: "bad_op"}) — finish 로 알릴 것"
        else -> failText(r)
    }

    /**
     * 쓰기 요청이 서버에 닿은 뒤의 실패가 「실행됐는지 모름」인가. 시간초과·끊김·연결 오류·서버가 불명이라 밝힌 것.
     * 서버가 「실행 안 함」이라 밝힌 것(대기열 시간초과 등)과 판정이 끝난 실패(bad_args·not_allowed·auth …)는 아니다.
     */
    private fun uncertain(r: AssistantClient.Reply): Boolean {
        val m = r.message.orEmpty()
        if ("실행 안 함" in m) return false
        return r.code in setOf("timeout", "cancelled", "unreachable", "server_error") || "불명" in m ||
            (r.code == "exec_failed" && m.startsWith("요청 실패("))
    }

    /** 비서 서버 실패 → 모델이 읽을 사유(계약 §3-1). 「실패: 」 머리는 부르는 쪽이 붙인다 */
    private fun failText(r: AssistantClient.Reply): String = when (r.code) {
        "tt_auth_expired" -> "캘린더 연동 인증 만료 — 홈 서버에서 캘린더 인증을 새로 고쳐야 함"
        "not_allowed" -> "${r.message ?: "시험 제한"} — 시험 제한이라 보내지 않았다 — 다시 시도하지 말고 finish"
        "auth" -> "홈 비서 토큰이 맞지 않다(폰 설정 › 홈 비서) — finish 로 알릴 것"
        "not_configured" -> "홈맥에 폰 연결이 설정되지 않았다(PHONE_API_TOKEN 없음) — finish 로 알릴 것"
        "timeout" -> "홈맥 응답 시간 초과${r.message?.let { " ($it)" } ?: ""}"
        else -> "${r.code ?: "unknown"}${r.message?.let { " — ${it.take(200)}" } ?: ""}"
    }

    private fun trashCaptures(keys: List<String>, all: Boolean): String {
        val mine = ownCaptures()
        fun n(x: String) = x.lowercase().replace(Regex("[\\s_]+"), "").replace(Regex("\\.(jpe?g|png|webp|gif)$"), "")   // image_gen·스킬 그림은 png 도 있다
        val picked = if (all) mine else keys.flatMap { k -> val key = n(k.substringAfterLast('/')); mine.filter { n(it.first) == key || n(it.first).contains(key) } }.distinct()
        if (picked.isEmpty()) return "실패: 지울 캡처를 못 찾음 — 최근 capture 사진: " + mine.take(12).joinToString(" · ") { it.first }
        trace.event("trash_captures", JSONObject().put("count", picked.size).put("files", JSONArray(picked.map { it.first })))
        if (!gate("사진 ${picked.size}장(${picked.first().first.take(30)} 등)", "휴지통으로 옮겨도")) return "사용자가 거부함 — 지우지 않았다"
        var ok = 0
        val cv = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_TRASHED, 1) }
        for ((_, uri) in picked) if (runCatching { svc.contentResolver.update(uri, cv, null, null) }.getOrDefault(0) > 0) ok++
        val left = ownCaptures().count { c -> picked.any { it.first == c.first } }
        return "휴지통으로 옮김: ${ok}/${picked.size}장 (남은 것 ${left}장 — 30일 안에 복구 가능)"
    }

    private fun shareImages(keys: List<String>, pkg: String): String {
        if (keys.isEmpty()) return "실패: files 가 비었음"
        val cr = svc.contentResolver
        val all = ArrayList<Pair<String, android.net.Uri>>()   // (이름, uri) 최신순
        cr.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DISPLAY_NAME),
            "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} LIKE 'FoldAgent_%'", null,
            "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && all.size < 200) all += c.getString(1) to android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
        }
        fun n(x: String) = x.lowercase().replace(Regex("[\\s_]+"), "").replace(Regex("\\.(jpe?g|png|webp|gif)$"), "")   // image_gen·스킬 그림은 png 도 있다
        val picked = ArrayList<Pair<String, android.net.Uri>>()
        val missing = ArrayList<String>()
        for (k in keys) {
            val key = n(k.substringAfterLast('/'))
            val hit = all.firstOrNull { n(it.first) == key && it !in picked } ?: all.firstOrNull { n(it.first).contains(key) && it !in picked }
            if (hit == null) missing += k else picked += hit
        }
        trace.event("share_images", JSONObject().put("keys", JSONArray(keys)).put("picked", JSONArray(picked.map { it.first })).put("missing", JSONArray(missing)).put("pkg", pkg))
        if (missing.isNotEmpty()) return "실패: 사진을 못 찾음 ${missing.joinToString { "「$it」" }} — 최근 capture 사진: " +
            all.take(12).joinToString(" · ") { it.first } + " (정확한 이름으로 다시)"
        val uris = ArrayList(picked.map { it.second })
        val i = (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                 else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris))
            .setType("image/*")   // 캡처는 jpg 지만 image_gen·스킬 그림은 png·webp 도 있다(계약 cc-capabilities §4)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // 여러 장은 ClipData 에도 실어야 읽기 권한이 모두 넘어간다
        i.clipData = android.content.ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
        val target = pkg.trim()
        return try {
            if (target.isNotEmpty()) svc.startActivity(i.setPackage(target))
            else svc.startActivity(Intent.createChooser(i, "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            Thread.sleep(1200)
            "공유 화면 열림: 사진 ${picked.size}장(${picked.joinToString { it.first }}) → ${target.ifEmpty { "공유 창" }}. 이제 받을 방·사람을 화면에서 고른다(전송 버튼은 사용자 확인)"
        } catch (e: android.content.ActivityNotFoundException) {
            "실패: ${target.ifEmpty { "공유" }} 앱이 사진 공유를 받지 않음"
        }
    }

    private fun openLink(uri: String, pkg: String): String {
        if (uri.isBlank()) return "uri 가 비었음"
        var u = android.net.Uri.parse(uri.trim())
        val scheme = u.scheme?.lowercase() ?: return "uri 형식이 아님: $uri"
        // 네이버 지도 appname 은 호출 앱 이름 — 모델이 com.openai.chatgpt 를 지어 넣었다(2026-09-26 E3). 우리 패키지로 고정
        if (scheme == "nmap" && u.getQueryParameter("appname") != svc.packageName) {
            val b = u.buildUpon().clearQuery()
            u.queryParameterNames.filter { it != "appname" }.forEach { k -> u.getQueryParameters(k).forEach { v -> b.appendQueryParameter(k, v) } }
            u = b.appendQueryParameter("appname", svc.packageName).build()
        }
        if (scheme == "intent" || scheme == "javascript") return "허용하지 않는 형식: $scheme"
        if (scheme in SENSITIVE_SCHEMES && !gate(uri.take(40), "금융 앱으로 열어도")) return "사용자가 거부함"
        val i = Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg.isNotBlank()) i.setPackage(pkg.trim())
        return try {
            svc.startActivity(i)
            // 대상 앱이 앞에 뜰 때까지 기다린다(최대 3초) — 곧바로 돌려주면 모델이 wait 를 끼워 넣었다(2026-09-26 E3·E4)
            val want = pkg.trim().ifEmpty { null }
            val end = SystemClock.uptimeMillis() + 3000
            Thread.sleep(500)
            while (want != null && SystemClock.uptimeMillis() < end && svc.rootInActiveWindow?.packageName?.toString() != want) Thread.sleep(250)
            "링크 열기: $u"
        } catch (e: android.content.ActivityNotFoundException) {
            // 모델이 검증된 coupang://search 를 두고 웹 주소를 지어 실패한 뒤 화면 조작으로 돌아갔다(2026-09-26 L2) — 실패한 자리에서 바로 알려 준다
            val known = if (pkg.isBlank()) emptyList() else Regex("✅ ([^:\n]+): `open_link\\(\"([^\"]+)\"").findAll(kb.appNotes(pkg.trim()).orEmpty())
                .map { "${it.groupValues[1]}=${it.groupValues[2]}" }.take(6).toList()
            "이 링크를 열 앱이 없음: $uri${if (pkg.isNotBlank()) " (패키지 $pkg)" else ""}" +
                if (known.isNotEmpty()) " — 이 앱의 검증된 딥링크: ${known.joinToString(" · ")}" else ""
        }
    }

    /** 화면을 바꾸지 않는 조회 도구 — 화면 재판독 없이 결과만 돌려준다. */
    private fun geocodeTool(step: Int, a: JSONObject): String {
        val r = try { geocode(a.optString("query")) } catch (e: Exception) { "실패: ${e.message}" }
        steps.put(JSONObject().put("tool", "geocode").put("summary", a.optString("query")))   // 성공 경로에도 남겨야 다음에 좌표를 지어내지 않는다
        trace.event("tool", JSONObject().put("step", step).put("name", "geocode").put("args", a).put("outcome", r))
        return r
    }

    private fun geocode(q: String): String {
        if (q.isBlank()) return "query 가 비었음"
        if (!android.location.Geocoder.isPresent()) return "이 폰에 지오코더가 없음 — 화면 조작으로 할 것"
        val g = android.location.Geocoder(svc, java.util.Locale.KOREA)
        val latch = java.util.concurrent.CountDownLatch(1)
        var found: List<android.location.Address> = emptyList()
        var err: String? = null
        g.getFromLocationName(q, 5, object : android.location.Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<android.location.Address>) { found = addresses; latch.countDown() }
            override fun onError(errorMessage: String?) { err = errorMessage; latch.countDown() }
        })
        if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) return "지오코더 응답 없음(8초)"
        if (err != null) return "지오코더 오류: $err"
        if (found.isEmpty()) return "'$q' 좌표를 못 찾음 — 주소로 다시 시도하거나 화면 조작으로 할 것"
        return found.mapIndexed { i, ad ->
            "${i + 1}) ${ad.getAddressLine(0) ?: ad.featureName} — 위도(y)=%.6f 경도(x)=%.6f".format(ad.latitude, ad.longitude)
        }.joinToString("\n", prefix = "좌표 후보(첫 번째가 가장 유력, 주소로 맞는지 확인):\n")
    }

    private fun openSettings(page: String, pkgOrName: String = ""): String {
        // 모델이 지식 노트의 바로가기를 두고도 표준 이름을 지어낸다 — 이 폰에 없는 흔한 이름은 실제로 열리는 액션으로 바꾼다
        // (2026-09-26 A3b·B2: BATTERY_SETTINGS·POWER_USAGE_SUMMARY 로 두 번 헛돌고 설정 앱을 뒤졌다)
        val action = SETTINGS_ALIAS[page.trim().removePrefix("android.settings.")] ?: page.trim().let { if (it.contains('.')) it else "android.settings.$it" }
        // CLEAR_TASK: 설정 앱이 다른 화면(앞 작업의 애플리케이션 목록 등)에 남아 있으면 새 화면을 열지 않고 그 창만 앞으로 가져왔다 —
        // 「화면 변화 없음」 2번 뒤 설정 앱을 돌아 들어갔다(2026-09-27 R2, 2026-09-27 run 101301-542 도 같은 증상)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val i = Intent(action).addFlags(flags)
        if (pkgOrName.isNotBlank()) {
            // 앱 이름으로 불러도 되게 — open_app 과 같은 규칙으로 패키지를 찾는다
            val pkg = resolvePkg(pkgOrName) ?: return "앱 '$pkgOrName' 을 못 찾음 — list_apps 로 확인할 것"
            // 앱 정보는 package: URI, 앱 알림 설정은 EXTRA_APP_PACKAGE 로 받는다(화면마다 다르므로 둘 다 넣는다)
            if (!action.contains("NOTIFICATION")) i.data = android.net.Uri.fromParts("package", pkg, null)
            i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)
        }
        return try {
            svc.startActivity(i); Thread.sleep(600); "설정 열기: $action"
        } catch (e: android.content.ActivityNotFoundException) {
            // 앱별 화면이 아닌데 package 를 붙이면 package: 데이터 때문에 안 잡힌다 — 모델이 설정 검색에 package=설정을 붙여
            // 「없는 설정 화면」으로 헛돈 뒤 설정 앱을 돌아 들어갔다(2026-09-27 평가 E6). package 없이 한 번 더
            if (pkgOrName.isNotBlank() && runCatching { svc.startActivity(Intent(action).addFlags(flags)) }.isSuccess) {
                Thread.sleep(600); "설정 열기: $action (앱 지정 없이 — 이 화면은 앱별 화면이 아니다)"
            } else "없는 설정 화면: $action — 설정 앱을 열어 찾아갈 것"
        } catch (e: SecurityException) {
            "열 수 없는 설정 화면(권한): $action"
        }
    }

    private fun setTimer(seconds: Int, label: String): String {
        if (seconds <= 0 || seconds > 24 * 3600) return "시간이 잘못됨: ${seconds}초"
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        svc.startActivity(i)
        return "타이머 설정: ${seconds / 60}분 ${seconds % 60}초"
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        if (hour !in 0..23 || minute !in 0..59) return "시각이 잘못됨: $hour:$minute"
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        svc.startActivity(i)
        return "알람 설정: %02d:%02d".format(hour, minute)
    }
}
