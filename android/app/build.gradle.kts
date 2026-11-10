plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// google-services.json は Git に入れない（CI で GitHub Secrets から作る）。無い時は Firebase 無しでビルドする
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "jp.oif.tsunaminavi"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.oif.tsunaminavi"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "API_BASE_URL", "\"https://tsunami-navi-129745156402.asia-northeast1.run.app\"")
        manifestPlaceholders["MAPS_API_KEY"] = System.getenv("MAPS_API_KEY") ?: ""
        buildConfigField("String", "LIVE_MODEL", "\"gemini-live-2.5-flash-native-audio\"")
        buildConfigField("String", "APP_CHECK_DEBUG_SECRET", "\"${System.getenv("APP_CHECK_DEBUG_SECRET") ?: ""}\"")
    }

    signingConfigs {
        getByName("debug") {
            // 毎回同じ鍵で署名する（入れ直しの時に消さなくて済む・Play Integrity の登録が変わらない）
            if (file("debug.keystore").exists()) {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":offline"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)
    implementation(libs.navigation.sdk)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.appcheck.debug)
}
