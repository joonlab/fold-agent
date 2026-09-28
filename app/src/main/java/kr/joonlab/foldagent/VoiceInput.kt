package kr.joonlab.foldagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 음성 입력 — MainActivity 에 있던 인식 로직을 그대로 옮긴 것(채팅 화면·빠른 입력 팝업이 같이 쓴다).
 * 누르면 시작(start), 한 번 더 누르면 끝(finish). 그 사이 인식기가 침묵으로 끊겨도 들은 문장을 모아 두고 다시 듣는다.
 * 마이크 권한 요청은 부르는 쪽(액티비티) 몫이다 — 권한이 없으면 start 는 아무것도 하지 않는다.
 * 모든 호출·콜백은 메인 스레드.
 */
class VoiceInput(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onState(listening: Boolean, ready: Boolean)      // 준비 중 / 듣는 중 (listening = 세션 진행 중)
        fun onPartial(text: String)                         // 지금까지 전사(segments + partial)
        fun onFinal(text: String, alts: List<String>, conf: FloatArray?)   // text 가 비었으면 아무 말도 못 들은 것
        fun onError(msg: String)
    }

    companion object { private const val TAG = "FoldAgent" }

    private val ui = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    private val speechErrors = JSONArray()   // 이번 듣기에서 난 인식 오류(폴백 포함) — 기록용

    private var sessionActive = false
    /** 「다 말했다」를 받고 최종 결과를 기다리는 중 */
    var finishing = false
        private set
    private val segments = ArrayList<String>()
    private var partial = ""
    private var lastAlts: List<String> = emptyList()
    private var lastConf: FloatArray? = null
    private var consecutiveErrors = 0
    private var recognizerIntent: Intent? = null
    private val submitFallback = Runnable { submit() }            // 끝 누른 뒤 최종 결과가 안 오면 모은 것으로 실행

    /** 듣기 세션이 진행 중인가(시작 ~ 제출/취소) */
    val active: Boolean get() = sessionActive

    // 인식기는 하나를 만들어 계속 재사용한다. 누를 때마다 destroy → create 하면 공유 음성 서비스 연결까지 끊겨
    // 새 인식기가 ERROR_SERVER_DISCONNECTED(11)로 죽고, 예비 인식기·재청취가 겹쳐 취소(5)가 연쇄로 난다(2026-09-26 로그).
    /** 이번 청취에서 엔진이 실제로 말을 받을 준비가 됐나(onReadyForSpeech) */
    var ready = false
        private set
    private var cuedThisSession = false
    private var retried11 = false
    private var preferOnDevice = true
    private val relistenRunnable = Runnable { if (sessionActive && !finishing) listen() }

    // 온디바이스 먹통 감지 — com.google.android.as 가 마이크는 열고 SODA 가 안 붙으면 ready 만 오고 RMS 가 0 이다
    // (개인 실측 메모 2026-09-23, 참고 앱 Dictation.kt 와 같은 판정).
    // 건강하면 첫 RMS 가 ~0.8초. 2초 안에 소리 신호가 없으면 먹통으로 보고 일반 엔진으로 내려가 60초 기억한다.
    private var heardAudio = false
    private var noOnDeviceUntil = 0L
    private val noAudioCheck = Runnable {
        if (!sessionActive || finishing || heardAudio || !usingOnDevice) return@Runnable
        Log.w(TAG, "ready 뒤 2초 동안 소리 신호가 없다 — 온디바이스 먹통으로 보고 일반 엔진으로")
        speechErrors.put(JSONObject().put("code", "wedged").put("onDevice", true))
        noOnDeviceUntil = System.currentTimeMillis() + 60_000
        recreate(false)
    }
    private fun onDeviceAllowed() = preferOnDevice && System.currentTimeMillis() > noOnDeviceUntil &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    private fun hasMic() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun state() = cb.onState(sessionActive, ready)

    /** 듣기 세션 시작. 마이크 권한은 부르는 쪽이 먼저 확인·요청한다. */
    fun start() {
        if (!hasMic()) return
        while (speechErrors.length() > 0) speechErrors.remove(0)
        segments.clear(); partial = ""; lastAlts = emptyList(); lastConf = null; consecutiveErrors = 0
        sessionActive = true; finishing = false; cuedThisSession = false; retried11 = false
        state()
        ensureRecognizer(onDeviceAllowed())
        listen()
    }

    /** 한 번의 청취 시작. 엔진이 준비될 때까지는 ready=false(그 전에 한 말은 버려진다). */
    private fun listen() {
        ui.removeCallbacks(relistenRunnable); ui.removeCallbacks(noAudioCheck)
        ready = false; heardAudio = false
        state()
        recognizerIntent?.let { i -> recognizer?.startListening(i) }
    }

    /** 사용자가 「다 말했다」고 알림 — 지금 듣던 조각의 최종 결과를 받고 onFinal. 2.5초 안에 안 오면 모은 것으로. */
    fun finish() {
        if (!sessionActive || finishing) return
        finishing = true
        ui.removeCallbacks(relistenRunnable)
        state()
        recognizer?.stopListening()
        ui.postDelayed(submitFallback, 2500)
    }

    fun cancel(quiet: Boolean = false) {
        if (!sessionActive) return
        sessionActive = false; finishing = false
        ui.removeCallbacks(submitFallback); ui.removeCallbacks(relistenRunnable); ui.removeCallbacks(noAudioCheck)
        recognizer?.cancel()
        state()
        if (!quiet) Log.i(TAG, "voice cancel")
    }

    private fun submit() {
        ui.removeCallbacks(submitFallback); ui.removeCallbacks(relistenRunnable); ui.removeCallbacks(noAudioCheck)
        if (!sessionActive) return
        sessionActive = false; finishing = false
        recognizer?.cancel()
        state()
        cb.onFinal(transcript(), lastAlts, lastConf)
    }

    /** 지금까지 들은 글(모은 조각 + 듣는 중인 조각) */
    fun transcript() = (segments + listOfNotNull(partial.takeIf { it.isNotBlank() })).joinToString(" ").trim()

    /** 실행 기록에 넣는 음성 메타(run 의 meta.stt) — 마지막 onFinal 뒤에 불러도 그 세션 값 그대로 */
    fun meta(): JSONObject = JSONObject()
        .put("onDevice", usingOnDevice)
        .put("segments", JSONArray(segments))
        .put("alternatives", JSONArray(lastAlts))   // 마지막 조각의 후보
        .put("confidence", lastConf?.let { c -> JSONArray(c.map { it.toDouble() }) } ?: JSONObject.NULL)
        .put("errors", JSONArray(speechErrors.toString()))

    /** 침묵으로 인식기가 끝나도 세션은 계속 — 다시 듣는다. 대기 중인 재청취는 항상 하나만. */
    private fun relisten(delayMs: Long = 120) {
        if (!sessionActive || finishing) return
        ui.removeCallbacks(relistenRunnable)
        ui.postDelayed(relistenRunnable, delayMs)
    }

    /** 인식기를 바꿔야 할 때만 — 파괴 직후 바로 만들면 연결이 또 끊기므로 잠깐 쉬었다 만든다. */
    private fun recreate(onDevice: Boolean) {
        ui.removeCallbacks(relistenRunnable)
        recognizer?.destroy(); recognizer = null
        ui.postDelayed({ if (sessionActive && !finishing) { ensureRecognizer(onDevice); listen() } }, 500)
    }

    private fun cue() {
        runCatching {
            val vm = ctx.getSystemService(android.os.VibratorManager::class.java)
            vm.defaultVibrator.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** 화면이 열릴 때 인식기를 미리 만들어 엔진·언어팩을 데워 둔다 — 첫 누름의 준비 시간을 줄인다. */
    fun prewarm() {
        if (!hasMic()) return
        if (recognizer != null) return
        ensureRecognizer(onDeviceAllowed())
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            recognizer?.checkRecognitionSupport(recognizerIntent!!, ctx.mainExecutor, object : android.speech.RecognitionSupportCallback {
                override fun onSupportResult(r: android.speech.RecognitionSupport) { Log.i(TAG, "recognizer warm: ${r.installedOnDeviceLanguages.take(3)}") }
                override fun onError(e: Int) { Log.w(TAG, "recognizer warm error $e") }
            })
        }
    }

    /** 화면이 사라질 때(onDestroy) — 대기 중인 재청취·제출을 버리고 인식기를 놓는다 */
    fun destroy() {
        ui.removeCallbacksAndMessages(null)
        sessionActive = false; finishing = false
        recognizer?.destroy(); recognizer = null
    }

    /** 필요한 종류의 인식기가 이미 있으면 그대로 쓴다. 없거나 종류가 다를 때만 만든다. */
    private fun ensureRecognizer(onDevice: Boolean) {
        if (recognizer != null && usingOnDevice == onDevice) return
        recognizer?.destroy()
        usingOnDevice = onDevice
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            private fun stale() = recognizer !== r     // 파괴된 옛 인식기에서 늦게 온 알림은 무시
            override fun onReadyForSpeech(p: Bundle?) {
                if (stale()) return
                ready = true; consecutiveErrors = 0
                ui.removeCallbacks(noAudioCheck)
                if (usingOnDevice) ui.postDelayed(noAudioCheck, 2000)
                if (sessionActive && !cuedThisSession) { cuedThisSession = true; cue() }
                if (!finishing) state()
            }
            override fun onBeginningOfSpeech() { if (!stale()) { heardAudio = true; ui.removeCallbacks(noAudioCheck) } }
            override fun onRmsChanged(v: Float) { if (!stale() && !heardAudio) { heardAudio = true; ui.removeCallbacks(noAudioCheck) } }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                if (stale()) return
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    partial = it
                    cb.onPartial(transcript())
                }
            }
            override fun onResults(b: Bundle?) {
                if (stale() || !sessionActive) return
                val alts = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                alts.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    segments += it; lastAlts = alts; lastConf = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                }
                partial = ""
                if (finishing) submit() else { cb.onPartial(transcript()); relisten() }
            }
            override fun onError(error: Int) {
                if (stale()) return
                Log.w(TAG, "speech error $error onDevice=$usingOnDevice ready=$ready finishing=$finishing segments=${segments.size}")
                if (!sessionActive) return                       // 우리가 cancel 한 뒤 오는 5번 등
                speechErrors.put(JSONObject().put("code", error).put("onDevice", usingOnDevice))
                if (finishing) { submit(); return }
                partial = ""
                when {
                    // 서비스 연결 끊김: 곧바로 예비 인식기로 가지 말고, 쉬었다가 같은 엔진으로 한 번 더
                    error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED && usingOnDevice && !retried11 -> { retried11 = true; recreate(true) }
                    usingOnDevice && error in listOf(SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) -> {
                        preferOnDevice = false; recreate(false)
                    }
                    // 침묵(못 알아들음·시간 초과)은 정상 — 계속 듣는다
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> relisten(120)
                    else -> {
                        consecutiveErrors++
                        if (consecutiveErrors > 6) {
                            cb.onError("음성 인식이 계속 실패해요($error) — 다시 눌러 주세요")
                            cancel(quiet = true)
                        } else relisten(400)                          // 5(클라이언트)·8(사용 중) 등 — 잠깐 쉬고
                    }
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // 엔진이 따라 주면 조각이 덜 잘린다(무시하는 엔진도 있어 위의 재청취가 본 장치다)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        // 자주 나오는 고유명사(지식 노트 「음성 인식 힌트」)로 인식기를 기울인다 — 아파트·가게 이름이 엉뚱한 말로 새는 것 방지
        val bias = AgentService.instance?.knowledge?.biasTerms() ?: Knowledge(ctx).biasTerms()
        if (Build.VERSION.SDK_INT >= 33 && bias.isNotEmpty()) i.putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(bias))
        recognizerIntent = i
    }
}
