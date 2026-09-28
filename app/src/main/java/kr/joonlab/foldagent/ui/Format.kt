package kr.joonlab.foldagent.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 글자 만들기 — 시각·날짜 머리·도구 이름·단계 묶음 요약. 화면 부품이 공유한다. */
object Fmt {
    private val hm = SimpleDateFormat("a h:mm", Locale.KOREAN)          // 「오후 9:41」
    private val md = SimpleDateFormat("M/d", Locale.KOREAN)
    private val mdLong = SimpleDateFormat("M월 d일", Locale.KOREAN)
    private val ymdLong = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREAN)

    fun dayStart(t: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val DAY = 86_400_000L

    /** 목록 줄 오른쪽 위: 오늘이면 「오후 9:41」, 어제면 「어제」, 그 전은 「9/24」 */
    fun rowTime(t: Long): String {
        val today = dayStart()
        return when {
            t >= today -> hm.format(Date(t))
            t >= today - DAY -> "어제"
            else -> md.format(Date(t))
        }
    }

    /** sticky 날짜 머리: 오늘 · 어제 · 9월 24일 · 지난해 이전은 2025년 9월 24일(해가 다른 같은 날짜가 구별되게) */
    fun dayHead(t: Long): String {
        val today = dayStart()
        return when {
            t >= today -> "오늘"
            t >= today - DAY -> "어제"
            year(t) == year(today) -> mdLong.format(Date(t))
            else -> ymdLong.format(Date(t))
        }
    }

    private fun year(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR)

    /** 대화방 메타의 시각: 「오늘 오후 9:12」 · 「어제 오전 8:02」 · 「9월 24일 오후 3:10」 */
    fun whenLong(t: Long): String = "${dayHead(t)} ${hm.format(Date(t))}"

    fun clock(t: Long): String = hm.format(Date(t))

    /** 경과 「41초」 · 「3분 12초」 */
    fun dur(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 60) "${s}초" else "${s / 60}분" + if (s % 60 > 0) " ${s % 60}초" else ""
    }

    /** 요청 출처 → 짧은 이름. 기록엔 voice·text·popup·adb 가 들어온다 */
    fun source(s: String): String = when (s) {
        "voice" -> "음성"; "text" -> "입력"; "popup" -> "빠른 입력"; "adb" -> "시험"; "job" -> "맡긴 일"; else -> ""
    }
    fun sourceIcon(s: String): String = when (s) { "voice" -> "mic"; "popup" -> "zap"; "adb" -> "terminal"; "job" -> "clock"; else -> "keyboard" }

    /** 단계 도구 → 사람 말. 모르는 도구는 이름 그대로 */
    fun tool(t: String): String = when (t) {
        "open_app" -> "앱 열기"; "tap" -> "탭"; "long_press" -> "길게 누르기"; "type_text" -> "입력"; "scroll" -> "스크롤"
        "swipe" -> "밀기"; "global" -> "이동"; "look" -> "화면 보기"; "note" -> "메모"; "capture" -> "캡처"
        "write_note" -> "노트 쓰기"; "open_link" -> "링크 열기"; "open_settings" -> "설정 열기"; "open_screen" -> "화면 열기"
        "set_alarm" -> "알람"; "set_timer" -> "타이머"; "wait" -> "기다림"; "geocode" -> "위치 찾기"; "list_apps" -> "앱 목록"
        "quick_toggle" -> "빠른 설정"; "share_images" -> "사진 공유"; "trash_captures" -> "캡처 지우기"; "stopwatch" -> "스톱워치"
        "media" -> "미디어"; "volume" -> "음량"; "brightness" -> "밝기"; "sound_mode" -> "소리 모드"; "clipboard" -> "클립보드"
        "flashlight" -> "손전등"; "system_setting" -> "시스템 설정"
        // 홈 비서(홈맥)
        "agenda" -> "일정 보기"; "briefing" -> "브리핑"; "mail_list" -> "메일 목록"; "mail_read" -> "메일 읽기"
        "memory_search" -> "기억 찾기"; "memory_get" -> "기억 보기"; "memory_add" -> "기억 추가"
        "event_create" -> "일정 만들기"; "event_delete" -> "일정 지우기"; "mail_send" -> "메일 보내기"; "mail_reply" -> "답장"
        // 웹·그림(직접 도구) · 스킬·실행·MCP(홈맥, 계약 cc-capabilities §1)
        "web_search" -> "웹 검색"; "read_url" -> "링크 읽기"; "image_gen" -> "그림"
        "skill_search" -> "스킬 찾기"; "skill_view" -> "스킬 읽기"; "run" -> "스킬 실행"; "code_run" -> "코드 실행"
        "mcp_tools" -> "MCP 도구 목록"; "mcp_call" -> "MCP 호출"; "job_status" -> "작업 상태"; "job_cancel" -> "작업 취소"
        else -> t
    }

    /** 목록 줄 왼쪽 타일 아이콘 — 마지막 턴의 마지막 도구 계열(§1-2). 없으면 말풍선 */
    fun toolIcon(t: String): String = when (t) {
        "open_app", "open_screen", "list_apps" -> "app"
        "type_text", "clipboard" -> "type"
        "tap", "long_press", "swipe", "scroll", "global" -> "tap"
        "write_note", "note" -> "note"
        "geocode", "open_link" -> "map"
        "open_settings", "quick_toggle", "volume", "brightness", "sound_mode", "flashlight", "media" -> "wrench"
        "capture", "share_images", "trash_captures" -> "camera"
        "look" -> "eye"
        "web_search", "read_url" -> "search"
        "image_gen" -> "camera"
        "skill_search", "skill_view", "run", "code_run", "mcp_tools", "mcp_call", "job_status", "job_cancel" -> "terminal"
        "agenda", "event_create", "event_delete" -> "clock"
        "mail_list", "mail_read", "mail_send", "mail_reply", "briefing", "memory_search", "memory_get", "memory_add" -> "note"
        "set_alarm", "set_timer", "stopwatch", "wait" -> "clock"
        else -> "chat"
    }

    /** 단계 묶음 한 줄: 「단계 6개 · 앱 열기 · 탭 ×3 · 입력」(관제실 rowsOf 방식 — 처음 나온 순서, 최대 4종) */
    fun stepsLine(tools: List<String>): String {
        val counts = LinkedHashMap<String, Int>()
        tools.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val parts = counts.entries.take(4).map { (k, n) -> tool(k) + if (n > 1) " ×$n" else "" }
        return "단계 ${tools.size}개" + parts.joinToString("") { " · $it" } + if (counts.size > 4) " …" else ""
    }

    /** 실패 계열 상태(ok·cancel·빈 값 말고 전부) */
    fun failed(status: String) = status.isNotEmpty() && status != "ok" && status != "cancel"

    fun statusLabel(s: String): String = when (s) {
        "failed" -> "실패"; "stuck" -> "막힘"; "limit" -> "단계 한도"; "error" -> "오류"; "cancel" -> "멈춤"; "paused" -> "이어서 하기 대기"; else -> s
    }
}
