plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val abis = (findProperty("bhasha.abis") as String? ?: "arm64-v8a").split(",").map { it.trim() }

android {
    namespace = "com.bhasha.engine.asr"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                // Always optimise native code: a debug-built model runtime is unusably slow.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    // Prebuilt sherpa-onnx + onnxruntime (v1.13.8) for the per-language speech models.
    sourceSets["main"].jniLibs.srcDir("../engine/third_party/sherpa-onnx/android/jniLibs")
    packaging {
        // Only the C API is used; the JNI and C++ wrappers would be dead weight.
        jniLibs { excludes += listOf("**/libsherpa-onnx-jni.so", "**/libsherpa-onnx-cxx-api.so") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.androidx.annotation)
}
