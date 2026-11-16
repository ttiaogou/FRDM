plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        val main by getting {
            kotlin.srcDir("../../app/src/main/java")
            kotlin.include("com/freedom/gesture/api/GestureApi.kt")
            kotlin.include(
                "com/freedom/core/accessibility/gesture/GestureIntent.kt",
                "com/freedom/core/accessibility/gesture/GestureTypes.kt"
            )
        }
    }
}

dependencies {
    api(project(":core"))
}
