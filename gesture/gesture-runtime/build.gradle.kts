plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        val main by getting {
            kotlin.srcDir("src/main/kotlin")
            kotlin.include(
                "com/freedom/core/accessibility/gesture/GestureCommand.kt",
                "com/freedom/core/accessibility/api/GestureExecutionPort.kt",
                "com/freedom/application/gesture/GestureExecutionCoordinator.kt",
                "com/freedom/application/gesture/GesturePolicyPipeline.kt",
                "com/freedom/application/gesture/GestureCommandCompiler.kt",
                "com/freedom/application/gesture/GesturePolicyValidator.kt",
                "com/freedom/application/gesture/GestureValidationTypes.kt",
                "com/freedom/application/gesture/GestureRuntimeFactory.kt",
                "com/freedom/application/gesture/GesturePolicyManager.kt"
            )
        }
        val test by getting {
            kotlin.srcDir("src/test/kotlin")
            kotlin.include("com/freedom/application/gesture/**")
        }
    }
}

dependencies {
    api(project(":core"))
    api(project(":gesture-api"))
    api(project(":gesture-core"))
    testImplementation("junit:junit:4.13.2")
}
