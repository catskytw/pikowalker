import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// Auto-incremented on every Gradle configure (i.e. every build invocation) and persisted to
// disk — a plain counter that only ever goes up, so "did this actually reinstall?" has an
// unambiguous answer regardless of any timestamp/cache ambiguity. See Settings > 版本資訊.
val versionPropsFile = file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
}
val buildNumber = (versionProps.getProperty("buildNumber")?.toIntOrNull() ?: 0) + 1
versionProps.setProperty("buildNumber", buildNumber.toString())
versionPropsFile.outputStream().use { versionProps.store(it, "Auto-incremented by build.gradle.kts — do not edit") }

// GitHub Releases 更新檢查用的 read-only token，放在 local.properties（已 gitignore）避免進 git
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val githubUpdateToken = localProps.getProperty("github.updateToken") ?: ""
val mapsApiKey = localProps.getProperty("maps.apiKey") ?: ""

android {
    namespace = "com.pikowalker.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pikowalker.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.1.$buildNumber"

        buildConfigField("String", "GITHUB_UPDATE_TOKEN", "\"$githubUpdateToken\"")
        buildConfigField("String", "GITHUB_OWNER", "\"catskytw\"")
        buildConfigField("String", "GITHUB_REPO", "\"pikowalker\"")

        manifestPlaceholders["mapsApiKey"] = mapsApiKey
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Health Connect (步數寫入)
    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")

    // DataStore (設定儲存)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // JSON 序列化 (儲存路線)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Google Maps (native vector rendering, proper density-aware label sizing, custom JSON styling)
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.maps.android:maps-compose:4.4.1")

    // Crashlytics (automatic crash/ANR upload — CrashLogger's local reports still need the user
    // to manually export and share them; this gets the same data without that step, including
    // from users other than whoever's debugging locally)
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-crashlytics")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
