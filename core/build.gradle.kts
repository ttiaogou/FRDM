plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        val main by getting {
            kotlin.srcDir("../app/src/main/java")
            kotlin.include("com/freedom/core/**")
            kotlin.exclude(
                "com/freedom/core/accessibility/gesture/GestureCommand.kt",
                "com/freedom/core/accessibility/gesture/GestureGeometryPolicy.kt",
                "com/freedom/core/accessibility/gesture/GestureDynamicsPolicy.kt",
                "com/freedom/core/accessibility/gesture/GestureIntent.kt",
                "com/freedom/core/accessibility/gesture/GesturePolicyPort.kt",
                "com/freedom/core/accessibility/gesture/GestureTypes.kt",
                "com/freedom/core/accessibility/api/GestureExecutionPort.kt"
            )
        }
        val test by getting {
            kotlin.srcDir("../app/src/test/java")
            kotlin.include("com/freedom/core/**")
            kotlin.exclude(
                "com/freedom/core/accessibility/gesture/GestureGeometryPolicyTest.kt",
                "com/freedom/core/accessibility/gesture/GestureDynamicsPolicyTest.kt"
            )
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
