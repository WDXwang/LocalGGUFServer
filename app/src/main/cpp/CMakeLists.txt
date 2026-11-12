cmake_minimum_required(VERSION 3.22.1)

project(localmodel C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_DIR ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)

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
set(GGML_VULKAN                 OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL                 OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI           OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(localmodel SHARED llama_jni.cpp)

target_compile_options(localmodel PRIVATE -O3 -fexceptions -frtti)

target_include_directories(localmodel PRIVATE
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}/ggml/include)

target_link_libraries(localmodel
        llama
        android
        log)
