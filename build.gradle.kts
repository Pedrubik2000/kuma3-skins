// kuma3 Skins: reps-only reviewers in several looks (Anki XP, Anki Hunger, Anki Joker). They read and answer kuma3 Anki's cards through
// AnkiDroid's content provider, so scheduling (FSRS-7), media and sync stay kuma3's job.
plugins {
    id("com.android.application") version "9.4.1"
}

android {
    namespace = "io.github.pedrubik2000.kuma3skins"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.pedrubik2000.kuma3skins"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    // release key from the environment (never in the repo); without it, release builds are unsigned
    val keystore = System.getenv("KUMA3SKINS_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("KUMA3SKINS_KEYSTORE_PASSWORD")
                keyAlias = "kuma3skins"
                keyPassword = System.getenv("KUMA3SKINS_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release { if (keystore != null) signingConfig = signingConfigs.getByName("release") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
