package kr.joonlab.foldagent.jobs

import android.content.Context
import android.util.Log
import kr.joonlab.foldagent.LlmClient
import kr.joonlab.foldagent.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 스크립트 출력 원문 → 사람에게 보일 답.
 *
 * - [tidy]: 결정적 정리. `\r` 로 덮어쓰는 진행률(yt-dlp 등)은 마지막 값만, 구분선(`====`·`────`)은 지우고, 같은 머리말의
 *   진행 줄이 이어지면 마지막 하나만 남긴다. 동기 run 결과(모델이 읽는 글)에도 쓴다 — 토큰만 먹고 뜻이 없는 줄들이라.
 * - [answer]: 맡긴 일(job) 결과 턴의 답. 대화 모델에 한 번 물어 요약한다(2026-09-27 사용자 실사용: 유튜브 요약 결과에
 *   구분선·토큰 수·비용 원문이, 영상 받기 결과엔 진행률 수십 줄이 그대로 답으로 떴다). 모델이 실패하면 정리한 원문을 쓴다.
 */
object JobSummary {
    private const val TAG = "FoldAgent"
    private val RULE = Regex("^\\s*([=\\-─━_*#~]\\s*){8,}$")
    /** 진행 줄 머리말 — `[download]`·`[youtube]` 처럼 대괄호로 시작하고 퍼센트·ETA 가 있는 줄 */
    private val PROGRESS = Regex("^(\\[[^\\]]{1,20}\\])\\s.*(\\d+(\\.\\d+)?%|ETA|it/s|MiB/s|KiB/s)")

    fun tidy(raw: String): String {
        val out = ArrayList<String>()
        var lastProgKey: String? = null
        for (line0 in raw.replace("\r\n", "\n").split('\n')) {
            // \r 로 덮어쓴 줄은 마지막 조각만(빈 조각은 건너뜀)
            val line = line0.split('\r').lastOrNull { it.isNotBlank() }?.trimEnd() ?: ""
            if (RULE.matches(line)) continue
            val prog = PROGRESS.find(line)?.groupValues?.get(1)
            if (prog != null && prog == lastProgKey && out.isNotEmpty()) { out[out.size - 1] = line; continue }
            lastProgKey = prog
            if (line.isBlank() && (out.isEmpty() || out.last().isBlank())) continue
            out.add(line)
        }
        return out.joinToString("\n").trim()
    }

    private const val SYS = """너는 휴대폰 비서의 결과 정리 담당이다. 사용자가 홈맥에 맡긴 일이 끝났고, 아래는 그 명령의 출력 원문이다.
사용자에게 보여 줄 답을 한국어 존댓말(~요)로 쓴다.
- 사용자가 원한 결과(요약·본문·찾은 값)가 원문에 있으면 그 내용을 살려 쓴다. 마크다운 제목(##)·목록은 유지해도 된다.
- 빼는 것: 진행률, 토큰 수, 구분선, 모델 이름·설정, 소요 시간, 내부 경로, 로그 머리말([youtube] 등).
- 비용이 적혀 있으면 끝에 한 줄로만(예: 「비용 약 73원」).
- 파일로 저장됐다면 「결과 파일은 첨부했어요」처럼 한 줄(첨부가 있을 때만). 파일을 만들었지만 첨부가 없다면 「파일은 홈맥 작업 폴더에 있어요」.
- 실패(종료코드≠0·오류)면 무엇이 안 됐는지와 원문에 보이는 이유만 짧게.
- 원문에 없는 내용은 지어내지 않는다. 답만 쓴다(머리말·인사 없이)."""

    /**
     * 끝난 job 의 답. [raw] 는 finalText 가 만든 글(종료코드·stdout·stderr). 실패하면 null — 부르는 쪽이 [tidy] 결과를 쓴다.
     * 네트워크 호출이라 감시 스레드에서만 부른다(40초 한도, 재시도 없음).
     */
    fun answer(ctx: Context, request: String, raw: String, attachNames: List<String>): String? {
        val clean = tidy(raw)
        if (clean.length < 200 && !clean.contains('\n')) return null   // 이미 짧다 — 모델 호출할 것 없음
        val user = buildString {
            append("맡긴 일: ").append(request.take(300)).append('\n')
            append("첨부: ").append(if (attachNames.isEmpty()) "없음" else attachNames.take(10).joinToString(", ")).append("\n\n")
            append("출력 원문:\n").append(clean.take(8000))
        }
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYS))
            .put(JSONObject().put("role", "user").put("content", user))
        val t0 = System.currentTimeMillis()
        return runCatching {
            LlmClient(Prefs.base(ctx), Prefs.model(ctx), Prefs.apiKey(ctx)).chatJson(msgs, Prefs.model(ctx), 40_000).raw.trim()
        }.onFailure { Log.w(TAG, "jobs: 결과 요약 실패 ${it.javaClass.simpleName}") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?.also { Log.i(TAG, "jobs: 결과 요약 ${clean.length}→${it.length}자 ${System.currentTimeMillis() - t0}ms") }
    }
}
