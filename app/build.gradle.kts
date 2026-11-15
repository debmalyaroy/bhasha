import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val abis = (findProperty("bhasha.abis") as String? ?: "arm64-v8a").split(",").map { it.trim() }

// The one place the version lives is `bhasha.version` in gradle.properties (MAJOR.MINOR.PATCH).
val appVersion = findProperty("bhasha.version") as String
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) ->
    major * 10_000 + minor * 100 + patch
}

// Release signing comes from the environment (CI) or from keystore.properties (local, not
// committed). Without either, release builds fall back to the debug key so they still install.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(name: String): String? =
    System.getenv("BHASHA_$name")?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(name)
val releaseKeystore = signingValue("KEYSTORE_FILE")

android {
    namespace = "com.bhasha.translator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bhasha.translator"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        ndk { abiFilters += abis }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = signingValue("KEYSTORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD") ?: signingValue("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    packaging {
        // llama.cpp picks its CPU backend by dlopen()ing a library file from
        // nativeLibraryDir, so the .so files must be extracted at install time.
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf("**/libsherpa-onnx-jni.so", "**/libsherpa-onnx-cxx-api.so")
        }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":asr"))
    implementation(project(":llm"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
