cmake_minimum_required(VERSION 3.22)
project(zero_llama LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# 导出编译命令，方便 IDE/静态分析正确解析 includePath
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# 这份 CMakeLists 在 app/src/main/cpp
set(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/include/llama.h")
    message(FATAL_ERROR "llama.cpp not found: ${LLAMA_DIR}")
endif()

# Android 常用开关（按需改）
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE  OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM64 ON CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_CUDA   OFF CACHE BOOL "" FORCE)
set(GGML_METAL  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# 引入 llama.cpp
add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama-build)

# 你的 JNI 动态库
add_library(llamajni SHARED
        llama_jni.cpp
)

# 👇 关键：用 BEFORE，把 include/ 和 ggml/include 预置到最前；千万不要加仓库根目录
target_include_directories(llamajni BEFORE PRIVATE
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}/ggml/include
        ${LLAMA_DIR}/ggml/src
        # 如需 grammar（llama-grammar.h），再加：
        # ${LLAMA_DIR}/src
)

# 显式加入 NDK sysroot 头文件目录，解决 IDE 提示找不到 jni.h / android/log.h
if (CMAKE_SYSROOT)
    message(STATUS "Android sysroot: ${CMAKE_SYSROOT}")
    target_include_directories(llamajni PRIVATE ${CMAKE_SYSROOT}/usr/include)
endif()

target_link_libraries(llamajni
        PRIVATE
        llama
        log
        android
)
