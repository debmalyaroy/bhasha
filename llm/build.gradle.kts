plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val abis = (findProperty("bhasha.abis") as String? ?: "arm64-v8a").split(",").map { it.trim() }

android {
    namespace = "com.bhasha.engine.llm"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_STL=c++_shared"
                // One CPU backend library per instruction-set level; the best one the
                // phone supports (dotprod / i8mm / SVE / SME) is picked at start-up.
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DGGML_OPENMP=OFF"
                arguments += "-DGGML_CCACHE=OFF"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=OFF"
                arguments += "-DLLAMA_BUILD_TESTS=OFF"
                arguments += "-DLLAMA_BUILD_TOOLS=OFF"
                arguments += "-DLLAMA_BUILD_EXAMPLES=OFF"
                arguments += "-DLLAMA_BUILD_SERVER=OFF"
                arguments += "-DLLAMA_BUILD_APP=OFF"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
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
