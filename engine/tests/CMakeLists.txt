# SPDX-License-Identifier: AGPL-3.0-or-later
# Host-side test tools for the engine (no Android needed).
#
# whisper.cpp and llama.cpp each bundle ggml, so they are configured as two
# separate builds:
#   cmake -S engine/tests -B build/asr -DBHASHA_TEST=asr && cmake --build build/asr --config Release
#   cmake -S engine/tests -B build/llm -DBHASHA_TEST=llm && cmake --build build/llm --config Release
cmake_minimum_required(VERSION 3.22)
project(bhasha_engine_tests LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

set(ENGINE_DIR ${CMAKE_CURRENT_LIST_DIR}/..)
set(BHASHA_TEST "asr" CACHE STRING "Which tool to build: asr or llm")

if(BHASHA_TEST STREQUAL "asr")
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    add_subdirectory(${ENGINE_DIR}/third_party/whisper.cpp whisper-build EXCLUDE_FROM_ALL)
    add_executable(asr_test asr_test.cpp ${ENGINE_DIR}/src/tr_asr.cpp)
    target_include_directories(asr_test PRIVATE ${ENGINE_DIR}/include)
    target_link_libraries(asr_test PRIVATE whisper)
    # Per-language CTC models through the prebuilt sherpa-onnx C API (Windows x64 host only).
    set(SHERPA_DIR ${ENGINE_DIR}/third_party/sherpa-onnx)
    if(WIN32 AND EXISTS ${SHERPA_DIR}/win/lib/sherpa-onnx-c-api.lib)
        target_compile_definitions(asr_test PRIVATE TR_WITH_SHERPA)
        target_include_directories(asr_test PRIVATE ${SHERPA_DIR}/include)
        target_link_libraries(asr_test PRIVATE ${SHERPA_DIR}/win/lib/sherpa-onnx-c-api.lib)
        add_custom_command(TARGET asr_test POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${SHERPA_DIR}/win/lib/sherpa-onnx-c-api.dll ${SHERPA_DIR}/win/lib/onnxruntime.dll
            ${SHERPA_DIR}/win/lib/onnxruntime_providers_shared.dll $<TARGET_FILE_DIR:asr_test>)
    endif()
else()
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    add_subdirectory(${ENGINE_DIR}/third_party/llama.cpp llama-build EXCLUDE_FROM_ALL)
    add_executable(llm_test llm_test.cpp ${ENGINE_DIR}/src/tr_llm.cpp)
    target_include_directories(llm_test PRIVATE ${ENGINE_DIR}/include
        ${ENGINE_DIR}/third_party/llama.cpp/include ${ENGINE_DIR}/third_party/llama.cpp/ggml/include)
    target_link_libraries(llm_test PRIVATE llama ggml)
endif()
