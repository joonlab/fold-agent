import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

// ── 개인 설정 주입 ──────────────────────────────────────────────
// 주소·모델 이름은 코드에 박지 않고 빌드할 때 넣는다. 우선순위:
//   ① gradle 속성(-Pfoldagent.llmBase=...) ② 환경변수(FOLDAGENT_LLM_BASE) ③ 루트 local.properties ④ 기본값
// 예시는 루트의 config.example.properties 참고. 토큰·API 키는 여기에 넣지 않는다(앱 설정 화면에서 입력).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, env: String, default: String): String =
    (findProperty(key) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: default
fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "kr.joonlab.foldagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.joonlab.foldagent"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // LLM(OpenAI 호환 /v1) 주소·모델. 기본은 OpenAI 공식 API — 키는 앱 설정에서 넣는다
        buildConfigField("String", "LLM_BASE", q(cfg("foldagent.llmBase", "FOLDAGENT_LLM_BASE", "https://api.openai.com/v1")))
        buildConfigField("String", "LLM_MODEL", q(cfg("foldagent.model", "FOLDAGENT_MODEL", "gpt-5.6-sol")))
        buildConfigField("String", "REPLAN_MODEL", q(cfg("foldagent.replanModel", "FOLDAGENT_REPLAN_MODEL", "gpt-6-astra")))
        buildConfigField("String", "REPLAN_FALLBACK_MODEL", q(cfg("foldagent.replanFallback", "FOLDAGENT_REPLAN_FALLBACK", "gpt-6-sol")))
        buildConfigField("String", "OAI_TOOL_MODEL", q(cfg("foldagent.oaiToolModel", "FOLDAGENT_OAI_TOOL_MODEL", "gpt-5.5")))
        // 홈 서버(비서 도구·스킬 게이트웨이·Claude Code 위임) 주소. 비워 두면 해당 도구는 「설정 없음」으로 실패한다
        // 계약서: docs/server-contract/CONTRACT.md · 목 서버: docs/server-contract/mock_server.py
        buildConfigField("String", "ASSISTANT_BASE", q(cfg("foldagent.assistantBase", "FOLDAGENT_ASSISTANT_BASE", "")))
        // 디버그 인텐트로 주소를 바꿀 때 허용할 호스트 접미사(예: 내 tailnet 의 MagicDNS 접미사). 비우면 ASSISTANT_BASE 와 같은 호스트만
        buildConfigField("String", "TRUSTED_HOST_SUFFIX", q(cfg("foldagent.trustedHostSuffix", "FOLDAGENT_TRUSTED_HOST_SUFFIX", "")))
        // 메일 도구 확인 창에 보여 줄 발신 주소(서버가 실제로 쓰는 주소와 맞춘다). 비우면 「서버 설정 주소」
        buildConfigField("String", "MAIL_FROM_LABEL", q(cfg("foldagent.mailFrom", "FOLDAGENT_MAIL_FROM", "")))
        // 재설치가 진짜 반영됐는지 화면에서 확인하려고 빌드 시각을 박는다.
        buildConfigField("String", "BUILD_TIME", "\"${SimpleDateFormat("MM-dd HH:mm:ss").format(Date())}\"")
    }
    buildFeatures { buildConfig = true; compose = true }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

// 앱 화면(홈·대화방·설정)만 Compose. 오버레이·빛·팝업은 View 그대로라 거기엔 의존이 없다(DESIGN §3-3).
// org.json · HttpURLConnection · SpeechRecognizer 는 여전히 플랫폼 내장.
dependencies {
    // 참고 앱 core 와 같은 조합(2026-09-20 foldlab 검증) — BOM 2026.08+ 는 compileSdk 37 을 요구한다.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")   // Text·DropdownMenu 정도만 쓴다
    implementation("androidx.activity:activity-compose:1.12.4")
}
