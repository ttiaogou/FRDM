pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Freedom"
include(":app", ":business", ":core", ":gesture-api", ":gesture-core", ":gesture-runtime", ":gesture-android", ":ocr-plugin-api", ":ocr-plugin")

project(":gesture-api").projectDir = file("gesture/gesture-api")
project(":gesture-core").projectDir = file("gesture/gesture-core")
project(":gesture-runtime").projectDir = file("gesture/gesture-runtime")
project(":gesture-android").projectDir = file("gesture/gesture-android")
