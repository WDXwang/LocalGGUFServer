# 手写 FindVulkan：Android 交叉编译时机器上通常没有 Vulkan SDK。
# NDK 自带三样东西，够 ggml-vulkan 用：
#   1. 头文件      <ndk>/toolchains/llvm/prebuilt/<host>/sysroot/usr/include/vulkan
#   2. 桩库        sysroot/usr/lib/<abi>/<api>/libvulkan.so（设备上由系统提供真实实现）
#   3. glslc       <ndk>/shader-tools/<host>/glslc(.exe)
#
# 只覆盖 ggml-vulkan 实际消费的接口：Vulkan::Vulkan、Vulkan_GLSLC_EXECUTABLE、
# Vulkan_glslc_FOUND（find_package 的 COMPONENTS 检查项）。
#
# 想换成真正的 Vulkan SDK，设环境变量 VULKAN_SDK 或传 -DVULKAN_GLSLC=<path> 即可优先使用。

set(Vulkan_FOUND FALSE)
set(Vulkan_glslc_FOUND FALSE)

set(Vulkan_INCLUDE_DIR "${CMAKE_SYSROOT}/usr/include")
if(EXISTS "${Vulkan_INCLUDE_DIR}/vulkan/vulkan.h")
    set(Vulkan_FOUND TRUE)
endif()

# 找 NDK 根目录：AGP 会传 CMAKE_ANDROID_NDK / ANDROID_NDK，
# 兜底再从 sysroot 往上 5 级（sysroot → host → prebuilt → llvm → toolchains → ndk）
set(_vk_ndk_root "")
foreach(_vk_cand IN ITEMS "${CMAKE_ANDROID_NDK}" "${ANDROID_NDK}"
        "$ENV{ANDROID_NDK_HOME}" "$ENV{ANDROID_NDK_ROOT}")
    if(NOT _vk_ndk_root AND _vk_cand AND EXISTS "${_vk_cand}/shader-tools")
        set(_vk_ndk_root "${_vk_cand}")
    endif()
endforeach()
if(NOT _vk_ndk_root)
    get_filename_component(_vk_ndk_root "${CMAKE_SYSROOT}/../../../../.." ABSOLUTE)
endif()

set(_vk_glslc "")
if(DEFINED VULKAN_GLSLC AND EXISTS "${VULKAN_GLSLC}")
    set(_vk_glslc "${VULKAN_GLSLC}")
elseif(DEFINED ENV{VULKAN_SDK} AND EXISTS "$ENV{VULKAN_SDK}/bin/glslc.exe")
    set(_vk_glslc "$ENV{VULKAN_SDK}/bin/glslc.exe")
elseif(DEFINED ENV{VULKAN_SDK} AND EXISTS "$ENV{VULKAN_SDK}/bin/glslc")
    set(_vk_glslc "$ENV{VULKAN_SDK}/bin/glslc")
else()
    file(GLOB _vk_glslc_candidates
            "${_vk_ndk_root}/shader-tools/*/glslc.exe"
            "${_vk_ndk_root}/shader-tools/*/glslc")
    if(_vk_glslc_candidates)
        list(GET _vk_glslc_candidates 0 _vk_glslc)
    endif()
endif()

set(Vulkan_GLSLC_EXECUTABLE "${_vk_glslc}")
if(_vk_glslc)
    set(Vulkan_glslc_FOUND TRUE)
endif()

if(Vulkan_FOUND AND NOT TARGET Vulkan::Vulkan)
    # NDK sysroot 只带 C 头（vulkan.h），ggml-vulkan 还要 C++ 的 vulkan.hpp，
    # 所以 VULKAN_HEADERS_DIR（Vulkan-Headers 仓库）存在时优先用它
    set(_vk_include_dirs "${Vulkan_INCLUDE_DIR}")
    if(VULKAN_HEADERS_DIR AND EXISTS "${VULKAN_HEADERS_DIR}/include/vulkan/vulkan.hpp")
        list(PREPEND _vk_include_dirs "${VULKAN_HEADERS_DIR}/include")
    elseif(DEFINED ENV{VULKAN_HEADERS_DIR} AND EXISTS "$ENV{VULKAN_HEADERS_DIR}/include/vulkan/vulkan.hpp")
        list(PREPEND _vk_include_dirs "$ENV{VULKAN_HEADERS_DIR}/include")
    endif()

    add_library(Vulkan::Vulkan INTERFACE IMPORTED)
    set_target_properties(Vulkan::Vulkan PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES "${_vk_include_dirs}"
            # 用 -lvulkan 交给 NDK 工具链在 sysroot 里解析对应 ABI 的桩库，
            # 不要写死 aarch64 路径（x86_64 变体也要能编）
            INTERFACE_LINK_LIBRARIES "vulkan")
endif()

message(STATUS "FindVulkan(NDK): found=${Vulkan_FOUND} include=${Vulkan_INCLUDE_DIR} glslc=${_vk_glslc}")
