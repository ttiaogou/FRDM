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
                "com/freedom/core/accessibility/gesture/GestureGeometryPolicy.kt",
                "com/freedom/core/accessibility/gesture/GestureDynamicsPolicy.kt",
                "com/freedom/core/accessibility/gesture/HorizontalSwipeConfig.kt",
                "com/freedom/core/accessibility/gesture/HorizontalSwipePolicy.kt"
            )
        }
        val test by getting {
            kotlin.srcDir("src/test/kotlin")
            kotlin.include(
                "com/freedom/core/accessibility/gesture/GestureGeometryPolicyTest.kt",
                "com/freedom/core/accessibility/gesture/GestureDynamicsPolicyTest.kt",
                "com/freedom/core/accessibility/gesture/HorizontalSwipePolicyTest.kt"
            )
        }
    }
}

dependencies {
    api(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
