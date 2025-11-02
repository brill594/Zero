cmake_minimum_required(VERSION 3.22)
project(zero_llama)
set(CMAKE_CXX_STANDARD 17)

# 允许切换 Vulkan（先关）
option(GGML_VULKAN "Enable Vulkan backend" OFF)

# 拉 llama.cpp 进来
add_subdirectory(${CMAKE_SOURCE_DIR}/../../third_party/llama.cpp ${CMAKE_BINARY_DIR}/llama-build)

add_library(llamajni SHARED
        llama_jni.cpp
)

target_include_directories(llamajni PRIVATE
        ${CMAKE_SOURCE_DIR}/../../third_party/llama.cpp
)

target_link_libraries(llamajni
        PRIVATE
        llama           # 由 llama.cpp 提供的静态/共享库
        log             # __android_log_print
        android
)
