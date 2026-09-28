package kr.joonlab.foldagent.records

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 「이번 요청을 어느 대화방에 넣을까」 — 빠른 입력 팝업과 대시보드 입력줄이 같은 규칙·같은 칩을 쓴다(2026-09-27 사용자 결정).
 * 기본: 현재 방이 1분 안에 갱신됐으면 그 방에 이어서(바로 덧붙이는 말), 아니면 새 대화.
 * 칩: [이어서·현재 방](1분 안일 때만) [＋ 새 대화] [최근 방 3개] — 하나만 고른다. 시간이 지났어도 최근 방 칩으로 이어 갈 수 있다.
 * (3분 기본이던 때는 메일·카톡·알람·음악 4건이 한 방에 쌓여 넷째 요청에 이전 대화 27개가 딸려 갔다)
 */
object RoomChoice {
    const val CONTINUE_MS = 60_000L
    private const val RECENT = 3

    /** id == null 이면 새 대화. label = 칩 글자, room = 보낸 뒤 표시에 쓰는 방 이름 */
    data class Option(val id: String?, val label: String, val room: String)
    data class Choice(val options: List<Option>, val defaultIndex: Int)

    /** IO 스레드에서 부를 것(목록을 읽는다) */
    fun build(store: RecordStore, windowMs: Long = CONTINUE_MS, now: Long = System.currentTimeMillis()): Choice {
        val cur = runCatching { store.currentId() }.getOrNull()
        val rooms = runCatching { store.list(RecordFilter()) }.getOrDefault(emptyList())
            .filter { it.turns > 0 }.sortedByDescending { it.updated }
        val curSum = rooms.firstOrNull { it.id == cur }
        // windowMs = 설정 「대화 이어 가기 시간」(Prefs.continueWindowSec) — 0 이면 늘 새 대화가 기본
        val fresh = curSum != null && windowMs > 0 && now - curSum.updated < windowMs
        val out = ArrayList<Option>()
        if (fresh) out += Option(curSum!!.id, "이어서 · ${short(curSum)}", short(curSum))
        out += Option(null, "＋ 새 대화", "새 대화")
        rooms.filter { !fresh || it.id != curSum!!.id }.take(RECENT).forEach { out += Option(it.id, "${short(it)} · ${whenShort(it.updated, now)}", short(it)) }
        return Choice(out, 0)
    }

    /** 고른 칩대로 현재 방을 바꾼다. 작업 중이면 select 가 IllegalStateException — 부르는 쪽이 busy 를 먼저 거른다. @return 들어갈 방 id */
    fun apply(store: RecordStore, opt: Option): String =
        if (opt.id == null) store.startNew() else { if (store.currentId() != opt.id) store.select(opt.id); opt.id }

    /** 보낸 직후 상태 패널 첫 줄 */
    fun sentLabel(opt: Option): String = if (opt.id == null) "＋ 새 대화" else "↳ 「${opt.room}」에 이어서"

    private fun short(s: RecordSummary): String {
        val t = s.title.ifBlank { s.lastRequest }.replace(Regex("\\s+"), " ").trim()
        return if (t.length > 12) t.take(11) + "…" else t
    }

    private fun whenShort(t: Long, now: Long): String {
        val a = Calendar.getInstance().apply { timeInMillis = t }
        val b = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) SimpleDateFormat("H:mm", Locale.KOREA).format(Date(t))
        else if (now - t < 2 * 86_400_000L) "어제" else SimpleDateFormat("M/d", Locale.KOREA).format(Date(t))
    }
}
