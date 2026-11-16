plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        val main by getting {
            kotlin.srcDir("../../app/src/main/java")
            kotlin.include(
                "com/freedom/core/accessibility/gesture/GestureGeometryPolicy.kt",
                "com/freedom/core/accessibility/gesture/GestureDynamicsPolicy.kt"
            )
        }
        val test by getting {
            kotlin.srcDir("../../app/src/test/java")
            kotlin.include(
                "com/freedom/core/accessibility/gesture/GestureGeometryPolicyTest.kt",
                "com/freedom/core/accessibility/gesture/GestureDynamicsPolicyTest.kt"
            )
        }
    }
}

dependencies {
    api(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
