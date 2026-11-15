# SPDX-License-Identifier: AGPL-3.0-or-later
# libbhasha_llm.so + libllama.so + libggml*.so (llama.cpp built as shared
# libraries so the CPU backend variant can be chosen at run time).
cmake_minimum_required(VERSION 3.22)
project(bhasha_llm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ENGINE_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../engine)
set(LLAMA_DIR ${ENGINE_DIR}/third_party/llama.cpp)

add_subdirectory(${LLAMA_DIR} llama-build)

add_library(bhasha_llm SHARED
    ${ENGINE_DIR}/src/tr_llm.cpp
    ${ENGINE_DIR}/jni/llm_jni.cpp)

target_include_directories(bhasha_llm PRIVATE
    ${ENGINE_DIR}/include
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include)
target_compile_options(bhasha_llm PRIVATE -fvisibility=hidden)
target_link_libraries(bhasha_llm PRIVATE llama ggml log)
target_link_options(bhasha_llm PRIVATE -Wl,-z,max-page-size=16384)
