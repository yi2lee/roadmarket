plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.roadmarket.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.roadmarket.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // 서버 주소는 이 값 하나예요 (7-0). 기본은 에뮬레이터에서 맥을 가리키는 주소, -PserverUrl=https://… 로 바꿔요.
        val serverUrl = (project.findProperty("serverUrl") as String?) ?: "http://10.0.2.2:8099"
        buildConfigField("String", "SERVER_URL", "\"" + serverUrl.trimEnd('/') + "\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
