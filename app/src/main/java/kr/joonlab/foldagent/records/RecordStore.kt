package kr.joonlab.foldagent.records

import org.json.JSONObject
import java.io.File

/*
 * 작업 기록 계약(DESIGN §4-3) — P0 이 만들고 동결한다. 화면(D)은 이 인터페이스만 쓰고,
 * 구현(C 의 FileRecordStore: 인덱스·전역 락·원자적 쓰기·휴지통)의 내부 배치에 기대지 않는다.
 * 코루틴 없이 콜백만 — View·Compose 양쪽에서 쓰려고.
 */

/** 목록 한 줄(세션 = 채팅방 하나). 인덱스에서 바로 만든다(턴·messages 를 읽지 않음) */
data class RecordSummary(val id: String, val title: String, val titleSource: String, val summary: String,
    val source: String, val pinned: Boolean, val archived: Boolean, val trashedAt: Long,
    val created: Long, val updated: Long, val turns: Int, val lastStatus: String, val lastRequest: String, val lastTool: String)

data class Step(val tool: String, val summary: String)

/** 대화방의 턴 카드 하나 = run 하나 */
data class TurnCard(val run: String, val title: String, val request: String, val final: String, val status: String,
    val t: Long, val ms: Long, val source: String, val rating: Boolean?, val steps: List<Step>, val confirms: List<ConfirmMark>,
    /** 도구들이 만든 첨부(계약 cc-capabilities §1-3). source="job" 이면 맡긴 작업 결과 턴 */
    val attachments: List<Attachment> = emptyList(),
    /** 사용자가 이 요청과 함께 폰에서 보낸 파일(계약 cc-capabilities/INPUTS §1) — 이름·크기만(원본 uri 는 저장하지 않는다) */
    val userFiles: List<UserFile> = emptyList())

/** 사용자 턴에 붙어 홈맥에 올린 파일. fileId = 홈맥 업로드 id(7일 보관) */
data class UserFile(val title: String, val mime: String?, val size: Long?, val fileId: String?)

/** 확인 게이트 기록(runs jsonl 의 confirm 이벤트에서). 기록일 뿐 — 여기서 다시 실행하지 않는다 */
data class ConfirmMark(val question: String, val result: String /* ok|denied|timeout */, val t: Long)

enum class Shelf { ACTIVE, ARCHIVED, TRASH }

data class RecordFilter(val shelf: Shelf = Shelf.ACTIVE, val includeTests: Boolean = false, val query: String = "",
    val status: String? = null, val pinnedOnly: Boolean = false, val since: Long = 0)

sealed interface RecordEvent {
    data class Changed(val id: String) : RecordEvent
    data class Removed(val id: String, val toTrash: Boolean) : RecordEvent
    data class Restored(val id: String) : RecordEvent
    data class Purged(val ids: List<String>, val runs: Int) : RecordEvent
    object Rebuilt : RecordEvent
}

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
