plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.roadmarket.phone"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.roadmarket.phone"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // 서버 주소는 이 값 하나예요. -PserverUrl=https://… 로 바꿔요.
        val serverUrl = (project.findProperty("serverUrl") as String?) ?: "https://relay-production-68643d09.up.railway.app"
        buildConfigField("String", "SERVER_URL", "\"" + serverUrl.trimEnd('/') + "\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// 의존성은 넣지 않아요. 안드로이드 기본 API와 Kotlin 표준 라이브러리만 써요.
