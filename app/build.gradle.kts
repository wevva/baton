import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.baton.app"
    compileSdk = 35
    // AAOS 표준 차량 API (운전석 위치 INFO_DRIVER_SEAT). 기기에 내장된 라이브러리를 쓴다
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.baton.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // 서명 키는 저장소 밖(C:/dev/keys)에 둔다. 경로는 환경변수 BATON_KEYSTORE_PROPS로 바꿀 수 있음
    val keyProps = Properties().apply {
        val f = file(System.getenv("BATON_KEYSTORE_PROPS") ?: "C:/dev/keys/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // 보안: 코드 축소·난독화(R8) + 리소스 축소, 디버그 로그 제거 (proguard-rules.pro)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // 단위 테스트에서 Android 스텁 대신 실제 org.json 구현 사용 (저장 형식 테스트용)
    testImplementation("org.json:json:20240303")
}

// 제출 규격: apk/팀명_앱이름_release.apk (팀명: 바통랩)
val submitApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("apk"))
    rename { "바통랩_바통_release.apk" }
}
afterEvaluate { tasks.named("assembleRelease") { finalizedBy(submitApk) } }
