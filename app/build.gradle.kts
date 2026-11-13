plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Vulkan 后端的构建开关：cd 到仓库根目录执行 gradle assembleDebug -Pvulkan
// 打开它需要构建机额外准备：NDK 自带的 glslc、一个 Windows 主机 C++ 编译器
// （用于编译 vulkan-shaders-gen）、以及 SPIRV-Headers 的 include 目录。
// 注意：命令行写 -Pvulkan 但不出值时属性值是空串，按"出现即开启"处理
val vulkanProperty = project.findProperty("vulkan") as String?
val vulkanEnabled = vulkanProperty != null &&
    vulkanProperty.lowercase() !in listOf("false", "0", "off", "no")
val vulkanValue = if (vulkanEnabled) "ON" else "OFF"
val spirvHeadersDir = (project.findProperty("spirvHeadersDir") as String?)
    ?: System.getenv("SPIRV_HEADERS_DIR")
    ?: ""
// Vulkan-Headers 仓库（提供 C++ 头 vulkan.hpp，NDK 里没有）
val vulkanHeadersDir = (project.findProperty("vulkanHeadersDir") as String?)
    ?: System.getenv("VULKAN_HEADERS_DIR")
    ?: ""

// 只打某一个 ABI 的包：gradle assembleDebug -PabiFilter=arm64-v8a
// 默认仍然双 ABI（arm64-v8a + x86_64），但编进 Vulkan 后单个 .so 约 32MB，
// 双 ABI 的 debug 包会到 88MB，发版时用这个开关只出 arm64 版本。
val abiFilter = (project.findProperty("abiFilter") as String?)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.workbuddy.localmodel"
    compileSdk = 35

    // 建议在 Android Studio 的 SDK Manager 中安装同版本 NDK 与 CMake
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.workbuddy.localmodel"
        // 28 而不是 26：ggml-vulkan 调用了 Vulkan 1.1 的 vkGetPhysicalDeviceFeatures2 等接口，
        // NDK 的 libvulkan.so 桩库按 API 级别裁剪，API 26/27 只有 Vulkan 1.0 符号。
        // 若把它们链进来却又在旧设备上跑，dlopen liblocalmodel.so 会因缺符号整体失败
        // （连纯 CPU 路径一起挂），所以这里直接把下限提到有 1.1 符号的 API 28。
        minSdk = 28
        targetSdk = 35
        versionCode = 12
        versionName = "1.0.11"

        ndk {
            abiFilters += if (abiFilter != null) listOf(abiFilter) else listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    // 只生成一个静态链接的 JNI 动态库，避免动态后端带来的打包复杂度
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_BUILD_MTMD=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                    "-DGGML_CPU_ALL_VARIANTS=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    // Vulkan 默认关闭；-Pvulkan 打开（需要 NDK glslc + 主机 C++ 编译器生成 shader）
                    "-DGGML_VULKAN=$vulkanValue",
                    "-DGGML_VULKAN_ENABLED=$vulkanValue",
                    "-DSPIRV_HEADERS_DIR=$spirvHeadersDir",
                    "-DVULKAN_HEADERS_DIR=$vulkanHeadersDir",
                    "-DGGML_OPENCL=OFF",
                    // Debug 变体默认给 C/C++ 传 -O0，llama.cpp 的量化矩阵乘在 -O0 下会慢数倍；
                    // 这里把 Debug 的优化等级拉到 -O3（保留 -g 便于栈回溯）
                    "-DCMAKE_C_FLAGS_DEBUG=-O3 -g",
                    "-DCMAKE_CXX_FLAGS_DEBUG=-O3 -g"
                )

                // 可选：编译期指定 ARM 目标微架构，启用 dotprod / i8mm / fp16 等指令。
                // 默认关闭：低于目标要求的机型会因非法指令 SIGILL 崩溃。
                // 用法：gradle assembleDebug -ParmArch=armv8.6-a+dotprod+i8mm+fp16
                //      -ParmDotprod 是 armv8.2-a+dotprod+fp16 的简写。
                val armArch = (project.findProperty("armArch") as String?)?.takeIf { it.isNotBlank() }
                    ?: if (project.hasProperty("armDotprod")) "armv8.2-a+dotprod+fp16" else null
                if (armArch != null) {
                    arguments += "-DGGML_CPU_ARM_ARCH=$armArch"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
