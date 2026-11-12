plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.workbuddy.localmodel"
    compileSdk = 35

    // 建议在 Android Studio 的 SDK Manager 中安装同版本 NDK 与 CMake
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.workbuddy.localmodel"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
                    "-DGGML_VULKAN=OFF",
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
