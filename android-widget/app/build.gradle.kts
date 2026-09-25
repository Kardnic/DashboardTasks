plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")

android {
    namespace = "de.kardnic.dashboardtaskswidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.kardnic.dashboardtaskswidget"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.3"

        buildConfigField("String", "SUPABASE_URL", "\"https://hfpryzswevnpmqdaidzj.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_odMpT_m3G4RihPHoaYFMKA_fz7NPVsy\"")
        buildConfigField("String", "DASHBOARD_URL", "\"https://kardnic.github.io/DashboardTasks/\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
}

dependencies {
    val supabaseVersion = "3.8.0"

    implementation(platform("io.github.jan-tennert.supabase:bom:$supabaseVersion"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.ktor:ktor-client-okhttp:3.5.1")

    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
