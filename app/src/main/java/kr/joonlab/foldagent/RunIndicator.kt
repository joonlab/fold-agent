package kr.joonlab.foldagent

/** 작업 중 표시의 국면 — 조작 중 · 모델 대기 · 확인 대기 */
enum class RunPhase { ACT, THINK, WAIT }

/**
 * AgentService 가 부르는 「작업 중」 표시 훅(테두리 빛 등). 전부 메인 스레드에서 호출된다(DESIGN §4-2).
 * 확인 게이트의 판정·시한과는 무관하다 — 표시만 한다.
 */
interface RunIndicator {
    fun start()                             // 작업 시작
    fun phase(p: RunPhase)                  // 확인 진입 WAIT / 퇴장 ACT / (선택) 모델 대기 THINK
    fun stop()                              // 작업 끝(성공·실패·정지 모두)
    fun setCaptureHidden(hidden: Boolean)   // 스크린샷 직전 true, 직후 false — 즉시 적용
    fun release()                           // onUnbind · 재연결
}
