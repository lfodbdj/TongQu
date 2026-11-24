import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.antidip"
    compileSdk = 34

    // Read master password from local.properties (gitignored) and expose it as
    // a BuildConfig field so it never lives in source/README (task B3).
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) load(f.inputStream())
    }
    // Read master password from local.properties (gitignored) and expose it as
    // a BuildConfig field so it never lives in source/README (task B3). No
    // hardcoded fallback literal — if unset, the parent master password is empty
    // and a build warning is emitted (decision #4: "密码移出源码").
    val masterPw = localProps.getProperty("ANTI_ADDICTION_MASTER_PASSWORD") ?: run {
        logger.warn(
            "ANTI_ADDICTION_MASTER_PASSWORD is not set in local.properties; " +
            "master password is DISABLED; only the date-derived password will work. " +
            "Set it to enable parent-mode unlock."
        )
        ""
    }

    defaultConfig {
        applicationId = "com.antidip"
        minSdk = 24
        targetSdk = 34
        versionCode = 15
        versionName = "2.3"

        buildConfigField("String", "MASTER_PASSWORD", "\"${masterPw}\"")
    }

    signingConfigs {
        // Debug signing uses the public debug keystore provided automatically by
        // the Android Gradle Plugin (safe to sideload) — no need to redefine it.
        // Release signing is configured via local.properties (gitignored) so the
        // key never lives in the repo (task B5). Only registered when present.
        val releaseStoreFile = localProps.getProperty("RELEASE_STORE_FILE")
        if (releaseStoreFile != null) {
            create("releaseSign") {
                storeFile = file(releaseStoreFile)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            // Uses AGP's default debug signing config (public debug keystore).
        }
        release {
            isMinifyEnabled = false
            val releaseStoreFile = localProps.getProperty("RELEASE_STORE_FILE")
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("releaseSign")
            }
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
        viewBinding = true
        buildConfig = true
    }
}

// Batch 3 (A30): export the Room schema so future migrations can be reviewed
// and version-controlled (task #12: exportSchema=true). History lives in
// app/schemas/com.antidip.db.QuizDatabase/<version>.json.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // AndroidX Startup (AppInitializer)
    implementation("androidx.startup:startup-runtime:1.1.1")

    // CardView (used in layouts)
    implementation("androidx.cardview:cardview:1.0.0")

    // Parent console (2-c): installed-app multi-select list.
    // 1.1.0 is the version Material 1.11.0 resolves to and is already cached.
    implementation("androidx.recyclerview:recyclerview:1.1.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // DataStore (persist quiz failure/lock state, task B2)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Unit tests (task B9)
    testImplementation("junit:junit:4.13.2")
}
