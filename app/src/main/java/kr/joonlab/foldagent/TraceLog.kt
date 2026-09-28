package kr.joonlab.foldagent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 실행 하나 = JSONL 파일 하나. 빠짐없이 남긴다 — 나중에 이걸로 지식을 만든다.
 * 위치: /sdcard/Android/data/kr.joonlab.foldagent/files/runs/<날짜>/<runId>.jsonl  (맥에서 ./dev.sh pull-logs)
 *
 * 이벤트: run_start · msg(모델과 주고받은 모든 메시지, 화면 전문 포함) · llm(지연·토큰) · tool(인자·결과·화면변화)
 *        · confirm · learn · guard · error · run_end · feedback
 */
class TraceLog(ctx: Context) {

    val runId: String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
    private val file: File = File(
        ctx.getExternalFilesDir("runs"), "${runId.substring(0, 8)}/$runId.jsonl"
    ).also { it.parentFile?.mkdirs() }

    @Synchronized
    fun event(type: String, data: JSONObject = JSONObject()) {
        runCatching {
            file.appendText(
                JSONObject().put("t", System.currentTimeMillis()).put("run", runId).put("type", type).put("data", data)
                    .toString() + "\n"
            )
        }
    }
}
