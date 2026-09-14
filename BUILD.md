# 构建说明

## 环境准备

### 1. 安装 Android Studio
- 下载 Android Studio (2023.1 或更高版本)
- 安装时选择 Android SDK 和 NDK

### 2. 配置 NDK
```bash
# 在 Android Studio 中:
Tools → SDK Manager → SDK Tools → 勾选 NDK (Side by side)
```

### 3. 克隆项目
```bash
cd DvplModHelper
```

## 构建步骤

### 方式一：使用 Android Studio（推荐新手）

1. **打开项目**
   ```
   File → Open → 选择 DvplModHelper 目录
   ```

2. **等待 Gradle 同步**
   - 首次打开会自动下载依赖
   - 需要几分钟时间

3. **连接设备或启动模拟器**
   - 真机：开启 USB 调试
   - 模拟器：AVD Manager 创建设备

4. **运行应用**
   ```
   Run → Run 'app' (Shift+F10)
   ```

5. **打包 APK**
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)（注：该菜单默认产出 debug 变体；release 用 ./gradlew assembleRelease）
   生成路径: app/build/outputs/apk/release/app-release.apk
   ```

### 方式二：命令行构建（推荐进阶用户）

1. **调试版本**
```bash
cd DvplModHelper
./gradlew assembleDebug

# APK 位置:
# app/build/outputs/apk/debug/app-debug.apk
```

2. **发布版本（需要签名）**
```bash
# 1. 生成签名密钥
keytool -genkey -v -keystore release.keystore -alias dvpl_key \
  -keyalg RSA -keysize 2048 -validity 10000

# 2. 配置签名（编辑 app/build.gradle.kts）
# 添加 signingConfigs 和 buildTypes

# 3. 构建
./gradlew assembleRelease

# APK 位置:
# app/build/outputs/apk/release/app-release.apk
```

3. **安装到设备**
```bash
# 自动安装到连接的设备
./gradlew installDebug

# 或手动安装
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 发布签名配置

### 创建 keystore.properties
```properties
storePassword=你的密码
keyPassword=你的密码
keyAlias=dvpl_key
storeFile=../release.keystore
```

### 修改 app/build.gradle.kts
```kotlin
// 在 android {} 块中添加
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

signingConfigs {
    create("release") {
        storeFile = file(keystoreProperties["storeFile"] as String)
        storePassword = keystoreProperties["storePassword"] as String
        keyAlias = keystoreProperties["keyAlias"] as String
        keyPassword = keystoreProperties["keyPassword"] as String
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        // ... 其他配置
    }
}
```

## 优化 APK 体积

### 已启用的优化
- ✅ ProGuard 代码混淆
- ✅ R8 全模式优化
- ✅ 资源压缩
- ✅ 仅 arm64-v8a 架构

### 预期体积
- **Debug 版本**: ~8-10 MB
- **Release 版本**: ~3-5 MB

### 进一步优化（可选）
```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        release {
            // 移除调试信息
            isDebuggable = false
            isJniDebuggable = false
            
            // 优化等级
            isMinifyEnabled = true
            isShrinkResources = true
            
            // 使用 ProGuard 完整优化
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

## 常见问题

### 1. Gradle 同步失败
```bash
# 清理缓存
./gradlew clean
./gradlew --refresh-dependencies
```

### 2. NDK 找不到
```bash
# 检查 NDK 路径
echo $ANDROID_NDK_HOME

# 设置环境变量
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/25.x.x
```

### 3. 编译 C++ 失败
```bash
# 检查 CMake
sdk manager "cmake;3.22.1"

# 清理 native 构建
rm -rf app/.cxx
./gradlew clean
```

### 4. 运行时崩溃
```bash
# 查看日志
adb logcat | grep DvplModHelper

# 检查权限
adb shell pm grant com.dvpl.modhelper android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.dvpl.modhelper android.permission.WRITE_EXTERNAL_STORAGE
```

## 测试

### 运行单元测试
```bash
./gradlew test
```

### 运行仪器化测试（需要设备）
```bash
./gradlew connectedAndroidTest
```

### 手动测试清单
- [ ] DVPL 解码（小文件 < 1MB）
- [ ] DVPL 解码（大文件 > 10MB）
- [ ] DVPL 编码（所有压缩类型）
- [ ] PVR → PNG 转换
- [ ] PNG → PVR 转换
- [ ] 批量处理（10+ 文件）
- [ ] 错误处理（损坏文件）
- [ ] 权限请求流程
- [ ] 中英文切换

## CI/CD

### GitHub Actions 自动构建
```bash
# 推送代码触发构建
git add .
git commit -m "Initial commit"
git push origin main

# 查看构建状态
# https://github.com/你的用户名/DvplModHelper/actions
```

### 下载构建产物
1. 访问 GitHub Actions
2. 点击最新的工作流运行
3. 下载 Artifacts 中的 APK
## 签名说明

release 构建默认使用本机 `~/.android/debug.keystore` 签名（便于直接安装）。若该文件不存在（如全新环境未构建过任何 debug 变体），构建仍会成功，产出未签名 APK，需自行 `apksigner` 签名后安装。
