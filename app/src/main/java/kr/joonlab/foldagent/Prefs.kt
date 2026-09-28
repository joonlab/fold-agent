package kr.joonlab.foldagent

import android.content.Context

/**
 * 앱 설정. 기본값은 빌드할 때 BuildConfig 로 들어온다(app/build.gradle.kts · config.example.properties).
 * 원래는 집에 있는 맥의 OpenAI 호환 프록시(사설망 전용)를 기본으로 썼다 — 공개본은 주소를 코드에 두지 않는다.
 */
object Prefs {
    val DEFAULT_BASE: String = BuildConfig.LLM_BASE
    // 2026-09-26 사용자 선택. 같은 장면 재질의에서 sol 3/3·3.0s > 5.5 2/3·4.8s > luna 1/3·4.3s (카톡 공유 시트 전송 버튼 판단)
    val DEFAULT_MODEL: String = BuildConfig.LLM_MODEL
    // 막혔을 때 한 번 물러나 다시 계획하는 호출(Replanner)의 모델. 2026-09-27 재생 실험 8/8 통과·「이미 실패한 동작」 재제안 0
    val DEFAULT_REPLAN_MODEL: String = BuildConfig.REPLAN_MODEL
    /** 홈 서버 폰 라우트 기본 주소 — docs/server-contract/CONTRACT.md. 비어 있으면 비서·스킬·위임 도구는 「설정 없음」 */
    val DEFAULT_ASSISTANT_BASE: String = BuildConfig.ASSISTANT_BASE
    /** 직접 도구(web_search·read_url·image_gen)가 LLM 주소의 /v1/responses 에 쓰는 모델 */
    val DEFAULT_OAI_TOOL_MODEL: String = BuildConfig.OAI_TOOL_MODEL

    private fun sp(c: Context) = c.getSharedPreferences("agent", Context.MODE_PRIVATE)

    fun base(c: Context) = sp(c).getString("base", DEFAULT_BASE)!!
    fun model(c: Context): String {
        // 한 번만: 예전 기본값(gpt-5.5·gpt-5.6-luna)으로 저장돼 있으면 새 기본값으로 옮긴다
        if (!sp(c).getBoolean("migrated_sol", false)) {
            val e = sp(c).edit().putBoolean("migrated_sol", true)
            if (sp(c).getString("model", null) in setOf("gpt-5.5", "gpt-5.6-luna")) e.putString("model", DEFAULT_MODEL)
            e.apply()
        }
        return sp(c).getString("model", DEFAULT_MODEL)!!
    }
    /** 재계획 모델 — 30초 안에 안 오면 replanFallback 으로 한 번 더 부른다.
     *  2026-09-27 재생 실험: astra 15/15·최대 23초, gpt-6-sol 은 66초로 튄 적 있지만 실패 동작 재제안 0 → 대체용. gpt-5.6-sol 은 재제안 3/22 */
    fun replanModel(c: Context) = sp(c).getString("replanModel", DEFAULT_REPLAN_MODEL)!!.ifBlank { DEFAULT_REPLAN_MODEL }
    fun setReplanModel(c: Context, v: String) = sp(c).edit().putString("replanModel", v.trim()).apply()
    fun replanFallback(c: Context) = sp(c).getString("replanFallback", BuildConfig.REPLAN_FALLBACK_MODEL)!!.ifBlank { BuildConfig.REPLAN_FALLBACK_MODEL }
    /** 직접 도구 모델(web_search·read_url·image_gen). 대화 모델(model)과 따로 둔다 — 호스티드 도구(web_search·image_generation)를 받는 모델이어야 해서 */
    fun oaiToolModel(c: Context) = sp(c).getString("oaiToolModel", DEFAULT_OAI_TOOL_MODEL)!!.ifBlank { DEFAULT_OAI_TOOL_MODEL }
    fun setOaiToolModel(c: Context, v: String) = sp(c).edit().putString("oaiToolModel", v.trim()).apply()
    fun apiKey(c: Context) = sp(c).getString("key", "")!!
    /** 앱을 열면 바로 듣기 — 기본 끔(사용자 2026-09-28). 설정 「열자마자 듣기」로 켤 수 있다 */
    fun autoListen(c: Context) = sp(c).getBoolean("autoListen", false)
    /** 답변·확인 안내를 음성(TTS)으로 읽을지 */
    fun speakAnswers(c: Context) = sp(c).getBoolean("speak", true)

    /** 지금 이어 가는 대화 세션 id — 「새 대화」면 새 id, 「기록」에서 고르면 그 id */
    fun sessionId(c: Context): String? = sp(c).getString("session", null)
    fun setSessionId(c: Context, id: String) = sp(c).edit().putString("session", id).apply()

    // ---- UI/UX 새 설계(DESIGN §4-2) — P0 이 키를 미리 둔다. 화면·빛·기록 작업자가 읽고 쓴다.

    /** 테마: system(기기 따라) · light · dark */
    fun theme(c: Context) = sp(c).getString("theme", "system")!!
    fun setTheme(c: Context, v: String) = sp(c).edit().putString("theme", v).apply()

    /** 테두리 빛 세기(가장자리 최대 알파 배율): 약 0.6 · 중 0.8 · 강 1.0 */
    fun glowStrength(c: Context) = sp(c).getFloat("glowStrength", 0.8f)
    fun setGlowStrength(c: Context, v: Float) = sp(c).edit().putFloat("glowStrength", v).apply()

    /** 테두리 빛 숨쉬기 주기(ms). 기본 = FaTokens.GLOW_PERIOD_MS */
    fun glowPeriodMs(c: Context) = sp(c).getLong("glowPeriodMs", 3600L)
    fun setGlowPeriodMs(c: Context, v: Long) = sp(c).edit().putLong("glowPeriodMs", v).apply()

    /** 휴지통 보관 기간(일) — 지나면 영구 삭제 대상 */
    fun trashDays(c: Context) = sp(c).getInt("trashDays", 30)
    fun setTrashDays(c: Context, v: Int) = sp(c).edit().putInt("trashDays", v).apply()

    /** 기록 목록에 adb 시험 세션도 보일지 */
    fun showTests(c: Context) = sp(c).getBoolean("showTests", false)
    fun setShowTests(c: Context, v: Boolean) = sp(c).edit().putBoolean("showTests", v).apply()

    /** 대화 제목을 LLM 으로 다듬을지(규칙 제목은 늘 즉시) */
    fun llmTitles(c: Context) = sp(c).getBoolean("llmTitles", true)
    fun setLlmTitles(c: Context, v: Boolean) = sp(c).edit().putBoolean("llmTitles", v).apply()

    /** 빠른 입력 팝업이 뜨자마자 듣기 */
    fun popupAutoListen(c: Context) = sp(c).getBoolean("popupAutoListen", true)
    fun setPopupAutoListen(c: Context, v: Boolean) = sp(c).edit().putBoolean("popupAutoListen", v).apply()

    /** 방법 고르기(choose_way) — 길이 여러 개면 처음에 선택 카드. 끄면 모델이 알아서 고른다(사용자 요청 2026-09-28) */
    fun askWay(c: Context) = sp(c).getBoolean("askWay", true)
    fun setAskWay(c: Context, v: Boolean) = sp(c).edit().putBoolean("askWay", v).apply()
    /** 선택 카드를 기다리는 시간(초) — 지나면 추천안으로 진행 */
    fun chooseWaitSec(c: Context) = sp(c).getInt("chooseWaitSec", 10).coerceIn(3, 60)

    /** 대화 이어 가기 시간(초) — 이 안에 다시 시키면 기본으로 같은 대화에 이어 붙인다. 0 = 끔(늘 새 대화). 2026-09-27 사용자: 설정에서 바꾸게 */
    fun continueWindowSec(c: Context) = sp(c).getInt("continueWindowSec", 60)
    fun setContinueWindowSec(c: Context, v: Int) = sp(c).edit().putInt("continueWindowSec", v).apply()

    /** 음성 답변 속도·높이(TextToSpeech 배수, 1.0 = 기본) */
    fun ttsRate(c: Context) = sp(c).getFloat("ttsRate", 1.0f)
    fun setTtsRate(c: Context, v: Float) = sp(c).edit().putFloat("ttsRate", v).apply()
    fun ttsPitch(c: Context) = sp(c).getFloat("ttsPitch", 1.0f)
    fun setTtsPitch(c: Context, v: Float) = sp(c).edit().putFloat("ttsPitch", v).apply()

    /** 음성 답변 길이 — "all" 전부 / "first" 첫 문장만(나머지는 화면으로) */
    fun ttsLength(c: Context) = sp(c).getString("ttsLength", "all")!!
    fun setTtsLength(c: Context, v: String) = sp(c).edit().putString("ttsLength", v).apply()

    /** 작업이 끝난 뒤 상태 패널이 남는 시간(초) — 읽는 중이면 다 읽을 때까지 남는다 */
    fun panelLingerSec(c: Context) = sp(c).getInt("panelLingerSec", 10)
    fun setPanelLingerSec(c: Context, v: Int) = sp(c).edit().putInt("panelLingerSec", v).apply()

    // ---- 홈 비서(홈 서버 /api/phone) — 계약 docs/server-contract/CONTRACT.md §2-1·§3

    /** 홈 서버 폰 전용 라우트 주소(https). 끝 「/」는 뺀다 */
    fun assistantBase(c: Context) = sp(c).getString("assistantBase", DEFAULT_ASSISTANT_BASE)!!.ifBlank { DEFAULT_ASSISTANT_BASE }
    fun setAssistantBase(c: Context, v: String) = sp(c).edit().putString("assistantBase", v.trim().trimEnd('/').ifEmpty { DEFAULT_ASSISTANT_BASE }).apply()

    /** 폰 전용 토큰(PHONE_API_TOKEN). 비어 있으면 비서 도구는 「설정 없음」으로 실패한다. 로그·trace 에 값을 남기지 않는다 */
    /**
     * 비서 주소로 받아 줄 값인가 — https 만(토큰이 평문으로 나가지 않게).
     * 인텐트(tailnetOnly)는 믿는 호스트만: BuildConfig.TRUSTED_HOST_SUFFIX(예: 내 사설망 MagicDNS 접미사)로 끝나거나,
     * 접미사가 비어 있으면 빌드 때 넣은 ASSISTANT_BASE 와 같은 호스트. 둘 다 없으면 인텐트로는 못 바꾼다(설정 화면에서만).
     */
    fun assistantBaseOk(v: String, tailnetOnly: Boolean = false): Boolean {
        val u = runCatching { java.net.URI(v.trim()) }.getOrNull() ?: return false
        if (!u.scheme.equals("https", true) || u.host.isNullOrBlank()) return false
        if (!tailnetOnly) return true
        val host = u.host.lowercase()
        val suffix = BuildConfig.TRUSTED_HOST_SUFFIX.trim().lowercase()
        if (suffix.isNotEmpty()) return host.endsWith(if (suffix.startsWith(".")) suffix else ".$suffix")
        val fixed = runCatching { java.net.URI(BuildConfig.ASSISTANT_BASE).host?.lowercase() }.getOrNull()
        return !fixed.isNullOrBlank() && host == fixed
    }

    fun assistantToken(c: Context) = sp(c).getString("assistantToken", "")!!
    fun setAssistantToken(c: Context, v: String) = sp(c).edit().putString("assistantToken", v.trim()).apply()

    fun save(c: Context, base: String, model: String, key: String, autoListen: Boolean, speak: Boolean) {
        sp(c).edit()
            .putString("base", base.trim().trimEnd('/').ifEmpty { DEFAULT_BASE })
            .putString("model", model.trim().ifEmpty { DEFAULT_MODEL })
            .putString("key", key.trim())
            .putBoolean("autoListen", autoListen)
            .putBoolean("speak", speak)
            .apply()
    }
}
