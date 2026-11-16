plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freedom.gesture.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

kotlin {
    sourceSets {
        getByName("main") {
            kotlin.srcDir("../../app/src/main/java")
            kotlin.include(
                "com/freedom/adapters/accessibility/android/AndroidGesturePathFactory.kt",
                "com/freedom/adapters/accessibility/android/AndroidGestureVisualizer.kt",
                "com/freedom/adapters/accessibility/android/AndroidGestureDispatcher.kt",
                "com/freedom/adapters/accessibility/android/GestureTrailOverlay.kt"
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":gesture-runtime"))
}
