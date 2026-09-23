plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "de.kardnic.dashboardtasks.widget"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.kardnic.dashboardtasks.widget"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SUPABASE_URL", "\"https://hfpryzswevnpmqdaidzj.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_odMpT_m3G4RihPHoaYFMKA_fz7NPVsy\"")
        buildConfigField("String", "DASHBOARD_URL", "\"https://kardnic.github.io/DashboardTasks/\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
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

kotlin {
    jvmToolchain(17)
}

dependencies {
    val supabaseVersion = "3.8.0"

    implementation(platform("io.github.jan-tennert.supabase:bom:$supabaseVersion"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.ktor:ktor-client-okhttp:3.5.1")

    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.google.android.gms:play-services-location:21.4.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
