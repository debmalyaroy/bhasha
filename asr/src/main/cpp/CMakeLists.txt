# SPDX-License-Identifier: AGPL-3.0-or-later
# libbhasha_asr.so: whisper.cpp (+ its own ggml) linked statically with hidden
# symbols, so it can live in the same process as llama.cpp's separate ggml.
cmake_minimum_required(VERSION 3.22)
project(bhasha_asr LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(ENGINE_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../engine)

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE            OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Baseline for every 64-bit phone core since 2018 (Cortex-A55/A75 onwards):
    # dot-product and half-precision arithmetic are what whisper's kernels use.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # Only the emulator runs this ABI. Without these the build falls back to
    # plain SSE and a sentence takes minutes to decode.
    set(GGML_AVX  ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
    set(GGML_FMA  ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${ENGINE_DIR}/third_party/whisper.cpp whisper-build EXCLUDE_FROM_ALL)

# SentencePiece (tokeniser of the translation model), built from source.
set(SPM_ENABLE_SHARED   OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
add_subdirectory(${ENGINE_DIR}/third_party/sentencepiece spm-build EXCLUDE_FROM_ALL)

add_library(bhasha_asr SHARED
    ${ENGINE_DIR}/src/tr_asr.cpp
    ${ENGINE_DIR}/src/tr_mt.cpp
    ${ENGINE_DIR}/jni/asr_jni.cpp
    ${ENGINE_DIR}/jni/mt_jni.cpp)

# Per-language CTC models (IndicConformer) run through the prebuilt sherpa-onnx
# C API; its .so files and onnxruntime are packaged from the same folder (see build.gradle.kts).
set(SHERPA_DIR ${ENGINE_DIR}/third_party/sherpa-onnx)
add_library(sherpa_c_api SHARED IMPORTED)
set_target_properties(sherpa_c_api PROPERTIES
    IMPORTED_LOCATION ${SHERPA_DIR}/android/jniLibs/${ANDROID_ABI}/libsherpa-onnx-c-api.so
    IMPORTED_NO_SONAME OFF)

# The translation model runs directly on the ONNX Runtime that sherpa-onnx brings along.
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION ${SHERPA_DIR}/android/jniLibs/${ANDROID_ABI}/libonnxruntime.so
    IMPORTED_NO_SONAME OFF)

target_include_directories(bhasha_asr PRIVATE ${ENGINE_DIR}/include ${SHERPA_DIR}/include
    ${ENGINE_DIR}/third_party/onnxruntime/include ${ENGINE_DIR}/third_party/sentencepiece/src)
target_compile_definitions(bhasha_asr PRIVATE TR_WITH_SHERPA)
target_compile_options(bhasha_asr PRIVATE -fvisibility=hidden)
target_link_libraries(bhasha_asr PRIVATE whisper sherpa_c_api onnxruntime sentencepiece-static log)
target_link_options(bhasha_asr PRIVATE
    -Wl,--exclude-libs,ALL
    -Wl,-Bsymbolic
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384)
