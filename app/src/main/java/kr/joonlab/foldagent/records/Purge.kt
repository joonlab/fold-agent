package kr.joonlab.foldagent.records

import org.json.JSONObject
import java.io.File

/**
 * 영구 삭제의 파일 쪽 일(FileRecordStore.purge 가 전역 락 안에서 부른다).
 * recipes.jsonl 재작성은 Knowledge.removeRecipeRuns — 그 파일의 락이 Knowledge 에 있어서.
 * 맥 사본(_data/phone)은 pull-logs 가 지우지 않으므로 거기엔 남는다 — 원장(purged.jsonl)으로 맥에서 따라 지울 수 있게 해 둔다.
 */
object Purge {
    private const val DAY_MS = 24L * 3600 * 1000

    /** 휴지통에 days 일 넘게 있었나(trashedAt 0 = 휴지통 아님) */
    fun expired(trashedAt: Long, now: Long, days: Int): Boolean = trashedAt > 0 && now - trashedAt >= days * DAY_MS

    /** runs/<날짜>/<runId>.jsonl 들을 지우고, 날짜 폴더가 비면 폴더도. 지운 파일 수 */
    fun deleteRuns(runsDir: File, runs: List<String>): Int {
        var n = 0
        for (run in runs) {
            if (run.length < 8 || run.contains('/') || run.contains("..")) continue   // 이상한 id 로 다른 파일을 지우지 않게
            val rf = File(runsDir, "${run.take(8)}/$run.jsonl")
            if (rf.exists() && rf.delete()) n++
            rf.parentFile?.let { d -> if (d.list()?.isEmpty() == true) d.delete() }
        }
        return n
    }

    /** 원장 줄들 — run 마다 {run, session, t}, 턴이 없던 방은 {session, t} 한 줄 */
    fun ledgerLines(session: String, runs: List<String>, now: Long): String {
        if (runs.isEmpty()) return JSONObject().put("session", session).put("t", now).toString() + "\n"
        return runs.joinToString("") { JSONObject().put("run", it).put("session", session).put("t", now).toString() + "\n" }
    }
}
