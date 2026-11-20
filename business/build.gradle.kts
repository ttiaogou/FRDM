plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        val main by getting {
            kotlin.srcDir("src/main/kotlin")
            kotlin.include("com/freedom/features/**")
        }
        val test by getting {
            kotlin.srcDir("src/test/kotlin")
            kotlin.include("com/freedom/features/**")
        }
    }
}

dependencies {
    api(project(":core"))
    api(project(":gesture-api"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
