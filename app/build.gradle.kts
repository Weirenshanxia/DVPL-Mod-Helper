plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dvpl.modhelper"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.dvpl.modhelper"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.2.2"
        
        // 只支持 arm64-v8a，大幅减小体积
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O2"   // astcenc 禁止 -ffast-math（浮点自检 BAD_CPU_FLOAT 会失败）
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26"
                )
            }
        }
    }
    
    // 签名：优先使用 debug keystore（本机开发直接可装）；不存在时 release 构建产出未签名 APK
    val debugKeystore = File(System.getProperty("user.home") + "/.android/debug.keystore")
    val hasKeystore = debugKeystore.exists()
    if (hasKeystore) {
        signingConfigs {
            create("releaseWithDebug") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("releaseWithDebug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // 启用 R8 完全优化模式
            ndk {
                debugSymbolLevel = "NONE"
            }
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
}

dependencies {
    // Compose UI - 使用 BOM 管理版本
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // 扩展图标库（R8 会自动裁剪未使用的图标）
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.1")
    
    // 核心库
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    
    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // 文档选择器
    implementation("androidx.documentfile:documentfile:1.0.1")
    
    // 调试工具
    debugImplementation("androidx.compose.ui:ui-tooling")
}