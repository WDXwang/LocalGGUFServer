cmake_minimum_required(VERSION 3.22.1)

project(localmodel C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_DIR ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)

# Vulkan 后端：默认关闭，只有 gradle -Pvulkan 时打开。
# 打开后需要额外的构建期依赖（NDK 的 glslc + 一个 Windows 主机 C++ 编译器来生成
# shader 源码，以及 SPIRV-Headers 的头文件目录），详见 cmake/FindVulkan.cmake。
option(GGML_VULKAN_ENABLED "启用 ggml Vulkan 后端" OFF)
if (GGML_VULKAN_ENABLED)
    list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake")
    list(APPEND CMAKE_PREFIX_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake/spirv")
    # find_package(SPIRV-Headers CONFIG) 直接指到 shim 所在目录，
    # 免得依赖 CMAKE_PREFIX_PATH 的搜索顺序在不同 CMake 版本上表现不一致
    if (NOT DEFINED SPIRV-Headers_DIR)
        set(SPIRV-Headers_DIR "${CMAKE_CURRENT_LIST_DIR}/cmake/spirv")
    endif ()
    # C++ Vulkan 头（vulkan.hpp）不在 NDK 里，由 gradle -PvulkanHeadersDir 或环境变量给出
    if (DEFINED ENV{VULKAN_HEADERS_DIR} AND NOT DEFINED VULKAN_HEADERS_DIR)
        set(VULKAN_HEADERS_DIR "$ENV{VULKAN_HEADERS_DIR}")
    endif ()
endif ()

if (NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "未找到 llama.cpp 源码目录: ${LLAMA_DIR}\n"
        "请先在项目根目录执行: bash scripts/setup_llama_cpp.sh")
endif ()

# ---- 精简 llama.cpp 构建，只保留推理内核 ----
set(BUILD_SHARED_LIBS            OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP             OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD            OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL               OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL                  OFF CACHE BOOL "" FORCE)

set(GGML_OPENMP                 OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL             OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS       OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE                 OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE              OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN                 ${GGML_VULKAN_ENABLED} CACHE BOOL "" FORCE)
set(GGML_OPENCL                 OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI           OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

if (GGML_VULKAN_ENABLED AND TARGET ggml-vulkan)
    # ggml-vulkan.cpp 是单文件上万行，-O3 下 clang 的峰值内存要到 2GB+，
    # 16GB 构建机（且被其它应用占满）上会直接把进程打死。
    # Vulkan 部分是"提交命令 + 调度"的外壳，真正吃 CPU 的内核在 ggml-cpu 里，
    # 降到 -O2 对推理速度基本无影响，峰值内存却明显下降。
    target_compile_options(ggml-vulkan PRIVATE -O2 -g0)

    # 上游 ggml-vulkan 只做了 find_package(SPIRV-Headers CONFIG REQUIRED)，却忘了把
    # 该包的 include 目录挂到 ggml-vulkan 目标上，于是 ggml-vulkan-types.h 引用
    # <spirv/unified1/spirv.hpp> 时找不到头。这里显式补上 SPIRV-Headers 的 include。
    if (DEFINED ENV{SPIRV_HEADERS_DIR} AND NOT DEFINED SPIRV_HEADERS_DIR)
        set(SPIRV_HEADERS_DIR "$ENV{SPIRV_HEADERS_DIR}")
    endif ()
    if (DEFINED SPIRV_HEADERS_DIR)
        target_include_directories(ggml-vulkan PRIVATE "${SPIRV_HEADERS_DIR}/include")
    endif ()
endif ()

add_library(localmodel SHARED llama_jni.cpp)

target_compile_options(localmodel PRIVATE -O3 -fexceptions -frtti)

target_include_directories(localmodel PRIVATE
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}/ggml/include)

target_link_libraries(localmodel
        llama
        android
        log)

# Android 15+ 的 16KB 页设备：NDK r27 默认按 4KB 对齐，出的 .so 在 16KB 页机器上
# 会被系统判定"不符合 16 KB 对齐要求"（启动即弹兼容性警告，加载/运行也可能异常）。
# 显式把 LOAD 区段对齐提到 16KB；同时保留 4KB 兼容（max-page-size 只放大不改小）。
target_link_options(localmodel PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
