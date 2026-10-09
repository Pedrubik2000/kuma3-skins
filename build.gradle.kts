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
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
