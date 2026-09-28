package kr.joonlab.foldagent.jobs

import android.content.Context
import android.util.Log

/**
 * 홈맥에 맡긴 작업(job) 등록 창구 — 계약 docs/server-contract/CONTRACT.md §1-2(오래 걸림)·§2(jobs long-poll)·§4(W-P3).
 *
 * Agent.capTool 이 「맡김」 결과마다 부른다. 여기선 JobStore 에 적고(파일 한 번 쓰기) 감시(JobWatcher)를 깨우기만 한다 —
 * 네트워크를 기다리지 않는다. 결과 턴 덧붙이기·첨부 받기·알림은 JobWatcher 가 한다.
 *
 * 부르는 스레드: 에이전트 작업 스레드(메인 아님). 같은 jobId 가 두 번 와도(재시도·replay) 한 번만 등록된다(멱등).
 */
object Jobs {
    /**
     * @param jobId 서버 job id
     * @param sessionId 맡긴 대화방(Session.id) — 결과 턴을 여기에 붙인다
     * @param runId 맡긴 실행(Agent.runId)
     * @param title 알림·칩에 보일 짧은 제목(요청 앞부분)
     * @param tool 맡긴 도구(run 등) · @param skill 스킬 이름(없으면 "")
     * @param depth 이어가기 사슬 깊이(계약 INPUTS §5) — 사람이 시킨 실행 = 1, 이어가기 실행 = 그 meta chainDepth + 1
     */
    fun register(ctx: Context, jobId: String, sessionId: String, runId: String, title: String, tool: String, skill: String, then: String = "", depth: Int = 1) {
        require(jobId.isNotBlank() && sessionId.isNotBlank()) { "jobId·sessionId 필요" }
        val isNew = JobStore.register(ctx, jobId, sessionId, runId, title, tool, skill, then, depth)
        Log.i("FoldAgent", "jobs: 등록 $jobId tool=$tool depth=$depth${if (then.isNotBlank()) " then" else ""}${if (isNew) "" else "(이미 있음)"}")
        if (isNew) wantNotifPermission = true
        JobWatcher.kick()
    }

    /**
     * 맡긴 작업이 생겼으니 알림 권한을 물어야 한다 — MainActivity 가 다음에 앞으로 올 때(onResume·finish) 보고 지운다.
     * 화면 조작 턴은 앱이 뒤에 있을 때 끝나고, 돌아오기 전에 작업이 이미 끝나면 pendingCount 가 0 이라 영영 안 묻던 틈을 막는다.
     */
    @Volatile var wantNotifPermission = false

    /** 결과를 아직 안 붙인 작업 수(칩) — 메모리 사본만 읽는다 */
    fun pendingCount(): Int = JobStore.pending.size
    fun pendingIn(sessionId: String): Int = JobStore.pending.count { it.sessionId == sessionId }
}
