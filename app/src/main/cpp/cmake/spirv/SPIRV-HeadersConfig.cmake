# 最小 SPIRV-Headers 包描述。
#
# ggml-vulkan 的 CMakeLists 里写了 find_package(SPIRV-Headers CONFIG REQUIRED)，
# 但实际只用到 spirv.hpp 头文件集合，不需要任何构建产物。
# 这里把 SPIRV_HEADERS_DIR 指向的 include 目录包成一个 imported target，
# 避免为了编 Vulkan 后端再去装/编一份 SPIRV-Headers。

set(SPIRV-Headers_FOUND TRUE)

if(NOT SPIRV_HEADERS_DIR AND DEFINED ENV{SPIRV_HEADERS_DIR})
    set(SPIRV_HEADERS_DIR "$ENV{SPIRV_HEADERS_DIR}")
endif()

if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES "${SPIRV_HEADERS_DIR}/include")
endif()

message(STATUS "SPIRV-Headers(prefix shim): include=${SPIRV_HEADERS_DIR}/include")
