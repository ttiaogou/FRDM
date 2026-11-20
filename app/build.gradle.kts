plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = providers.gradleProperty("releaseStoreFile").orNull
val releaseStorePassword = providers.gradleProperty("releaseStorePassword").orNull
val releaseKeyAlias = providers.gradleProperty("releaseKeyAlias").orNull
val releaseKeyPassword = providers.gradleProperty("releaseKeyPassword").orNull
val releaseSigningConfigured = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    signingConfigs {
        if (releaseSigningConfigured) {
            create("freedomRelease") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    namespace = "com.freedom"
    compileSdk = 35

    buildTypes {
        debug {
            // Temporary local-only OCR validation. Release artifacts must never log chat bodies.
            buildConfigField("boolean", "OCR_RESULT_DEBUG_LOGGING", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "OCR_RESULT_DEBUG_LOGGING", "false")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("freedomRelease")
            }
        }
    }

    defaultConfig {
        applicationId = "com.freedom"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("freedomVersionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("freedomVersionName").orNull ?: "1.0.0"
        val pluginManifestUrl = providers.gradleProperty("ocrPluginManifestUrl").orNull.orEmpty()
        val escapedPluginManifestUrl = pluginManifestUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "OCR_PLUGIN_RELEASE_MANIFEST_URL", "\"$escapedPluginManifestUrl\"")
        val pluginCatalogUrl = providers.gradleProperty("ocrPluginCatalogUrl").orNull.orEmpty()
        val escapedPluginCatalogUrl = pluginCatalogUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "OCR_PLUGIN_CATALOG_URL", "\"$escapedPluginCatalogUrl\"")
    }

    buildFeatures {
        buildConfig = true
        aidl = true
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

dependencies {
    implementation(project(":business"))
    implementation(project(":core"))
    implementation(project(":gesture-api"))
    implementation(project(":gesture-runtime"))
    implementation(project(":gesture-android"))
    implementation(project(":ocr-plugin-api"))
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
