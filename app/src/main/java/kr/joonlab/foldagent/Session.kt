package kr.joonlab.foldagent

import android.content.Context
import android.util.Log
import kr.joonlab.foldagent.records.Attachments
import kr.joonlab.foldagent.records.FileRecordStore
import kr.joonlab.foldagent.records.RecordFilter
import kr.joonlab.foldagent.records.Records
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 대화 세션 — 요청·도구 내역·답변을 턴 단위로 쌓아 다음 요청이 이어받는다(멀티턴).
 * 파일 하나 = 세션 하나: /sdcard/Android/data/kr.joonlab.foldagent/files/sessions/<id>.json
 *
 * 이제 기록 저장소(records/FileRecordStore)를 감싼 얇은 어댑터다. 예전엔 이 인스턴스가 메모리 사본을 들고 있다가
 * 실행 끝에 통째로 덮어써서, 실행 도중 UI 에서 바꾼 제목·고정이 지워졌다 → 쓰기는 전부 저장소(디스크 재읽기 + 원자적 쓰기)로.
 * title·turns 는 불러온 순간의 읽기 전용 사본이다(다음 요청의 맥락은 historyMessages 가 매번 디스크에서 읽는다).
 *
 * 저장하는 메시지는 화면 전문·앱 요령을 뺀 가벼운 형태다(전문은 runs/ 기록에 있다).
 * 모델에 넘길 때: 최근 3턴은 도구 내역까지 그대로, 그 이전은 「요청 → 결과」 요약만.
 */
class Session private constructor(val id: String, private val preTurns: Int? = null,
                                  private val preTitle: String? = null, private val preUpdated: Long? = null) {

    private val doc: JSONObject by lazy { (Records.store as? FileRecordStore)?.raw(id) ?: JSONObject() }

    val title: String get() = preTitle ?: doc.optString("title")
    val created: Long get() = doc.optLong("created", System.currentTimeMillis())
    val updated: Long get() = preUpdated ?: doc.optLong("updated", created)
    /** 불러온 순간의 턴들(읽기 전용 사본) */
    val turns: JSONArray get() = doc.optJSONArray("turns") ?: JSONArray()

    val isEmpty get() = (preTurns ?: turns.length()) == 0

    /**
     * 이번 턴을 저장소에 붙인다. source(voice/text/popup/adb)·ms 는 v2 에 새로 남기는 값.
     * 저장 실패가 실행 결과 보고(finish·speak)를 막으면 안 되므로 여기서 삼키고 로그만.
     */
    fun addTurn(runId: String, request: String, finalMsg: String, status: String, steps: JSONArray, messages: List<JSONObject>,
                source: String = "", ms: Long = 0L, attachments: JSONArray = JSONArray(), userFiles: JSONArray = JSONArray()) {
        val turn = JSONObject().put("run", runId).put("request", request).put("final", finalMsg).put("status", status)
            .put("steps", steps).put("messages", JSONArray(messages.map { slim(it) }))
            .put("t", System.currentTimeMillis()).put("ms", ms).put("source", source)
        // 첨부(계약 cc-capabilities §1-3): 알려진 키만 남겨 턴에 둔다. 다음 턴 맥락엔 저장소 historyMessages 가 요약 한 줄만 싣는다
        val atts = runCatching { Attachments.sanitize(attachments) }.getOrDefault(JSONArray())
        if (atts.length() > 0) turn.put("attachments", atts)
        // 사용자가 폰에서 보낸 파일(계약 INPUTS §1) — 이름·mime·크기·fileId 만(content uri 는 권한이 사라지므로 남기지 않는다)
        if (userFiles.length() > 0) turn.put("userFiles", userFiles)
        runCatching { Records.store.appendTurn(id, turn, source) }
            .onFailure { Log.e("FoldAgent", "session: 턴 저장 실패 $id", it) }
    }

    /** 이번 요청 앞에 붙일 이전 대화 — 저장소가 디스크에서 읽어 만든다 */
    fun historyMessages(): List<JSONObject> = runCatching { Records.store.historyMessages(id) }.getOrDefault(emptyList())

    fun label(): String {
        val f = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        return "${title.ifEmpty { "(빈 대화)" }}  ·  ${f.format(Date(updated))}  ·  ${preTurns ?: turns.length()}턴"
    }

    companion object {
        private const val SCREEN_MARK = "[현재 화면]"

        fun newId(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        fun load(ctx: Context, id: String): Session {
            Records.init(ctx)
            return Session(id)
        }

        /** 지금 이어 가는 세션(없으면 새로). */
        fun current(ctx: Context): Session {
            Records.init(ctx)
            return load(ctx, Records.store.currentId())
        }

        fun startNew(ctx: Context): Session {
            Records.init(ctx)
            return load(ctx, Records.store.startNew())
        }

        /** 활성 세션들(고정 먼저, 최근 것부터) — 휴지통·보관·빈 세션 제외, 시험 세션 포함(옛 동작). 목록 값은 인덱스에서 */
        fun all(ctx: Context): List<Session> {
            Records.init(ctx)
            return Records.store.list(RecordFilter(includeTests = true)).map {
                Session(it.id, preTurns = it.turns, preTitle = it.title, preUpdated = it.updated)
            }
        }

        /** 저장용으로 가볍게: 화면 전문과 앱 요령은 뺀다(모델이 다음 턴에 다시 볼 필요 없음). */
        private fun slim(m: JSONObject): JSONObject {
            val c = m.opt("content")
            if (c !is String) return m
            var t = c
            val i = t.indexOf("\n\n[앱 요령")
            val j = t.indexOf(SCREEN_MARK)
            val cutAt = listOf(i, j).filter { it >= 0 }.minOrNull()
            if (cutAt != null) t = t.substring(0, cutAt).trimEnd() + " (화면 생략)"
            return JSONObject(m.toString()).put("content", t)
        }
    }
}
