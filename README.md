# DVPL Mod Helper（DVPL Mod 助手）

![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg)
![API](https://img.shields.io/badge/API-26%2B-orange.svg)
![Size](https://img.shields.io/badge/APK-~3MB-informational.svg)

**专为坦克世界闪击战（WoT Blitz）Mod 开发者设计的安卓端图形化资源转换工具**

在手机上完成 DVPL 解包/打包、ASTC 纹理与 PNG 互转、PC ↔ 安卓贴图跨端移植——无需电脑。

## 下载

前往 [Releases](https://github.com/Weirenshanxia/DVPL-Mod-Helper/releases) 下载最新 APK（Android 8.0+ / arm64 设备，无需任何权限）。

## 功能特性

### 📦 DVPL 编解码
- 支持 DVPL 压缩格式：无压缩(0)、LZ4(1)、LZ4_HC(2)、DEFLATE(3)
- 高性能 C++ Native 实现（LZ4/zlib 原生库 + 纯 Kotlin 回退）
- 批量处理，多文件并行（4 并发）；逐文件成功/失败明细对话框
- CRC32 严格校验：文件损坏直接报错，不会让坏数据流入游戏
- 注意：DEFLATE(type 3) 为本工具自有扩展，游戏客户端已知仅使用 type 0/1/2

### 🖼️ 纹理转换（全格式）
- **PVR → PNG**：ASTC 4x4~12x12 全档（LDR/HDR 自动识别、HDR 自动色调映射）、未压缩 RGBA8888/R8、PC 端 RGBA4444
  - **WoT Blitz 非标准 ASTC 枚举全兼容**：游戏使用自有 pfLo 枚举表（非 PVR v3 标准），本工具按 mip 链数据长度反推真实块尺寸，pfLo 27-45 全部支持
- **PNG → PVR**：ASTC 4x4/5x5/6x6（推荐）/8x6/10x5、RGBA8888 无损（自动生成完整 mipmap 链）、RGBA4444（PC 端 DX11 格式）
- **DDS → PNG**：BC1(DXT1)/BC2(DXT3)/BC3(DXT5)/BC4/BC5/BC6H(HDR)/BC7 全格式解码（基于 bcdec），DVPL 包裹的 .dds.dvpl 自动解包
- **PNG → DDS**：BC3（标准）/ BC5（法线图）/ BC4（单通道）
- **DDS ↔ PVR 跨端移植**：PC mod 转安卓、安卓转 PC
- 注意：ASTC/BC 系列均为有损压缩；仅 RGBA8888 选项为无损

### 👁 纹理预览（GPU 直显）
- ASTC 压缩数据经 glCompressedTexImage2D 直接交给 GPU 硬件解码，零临时文件
- 双指缩放 / 拖动 / mipmap 级切换
- GPU 不支持时自动回退软件解码
- 支持导出 PNG

### 📱 极简操作
- 系统文件选择器（SAF）选择文件，无需任何存储权限
- 可自定义导出目录（持久化授权）；默认导出到 Download/DVPLModHelper
- 同名文件自动加编号，不会覆盖

### 🚀 性能
- 安装包约 3 MB，仅 arm64-v8a（覆盖绝大多数现代手机）
- astcenc（ARM 官方 ASTC 编解码器）NEON 优化
- 批量转换并行处理；整个文件读入内存处理（超大文件受可用内存限制）

## 使用说明

### 基本流程
1. 选择转换模式（如 "PVR → PNG"）
2. 从系统文件选择器选文件（可多选）
3. 转换完成，结果保存在导出目录（默认 Download/DVPLModHelper，可在"导出目录"卡片修改）

### Android 11+ 访问游戏目录提示
Android 11+ 的 SAF 文件选择器无法进入其他应用的 Android/data 目录。
建议先用系统文件管理器（如 Material Files）或 adb 把游戏资源复制到普通目录（如 Download）再转换。

## 技术栈
- Kotlin + Jetpack Compose（Material 3）
- C++ JNI：astcenc（Apache-2.0）、bcdec（MIT）、LZ4（BSD-2）、zlib
- GLSurfaceView + OpenGL ES 3.0 纹理预览

## 构建
见 [BUILD.md](BUILD.md)。需要 Android SDK / NDK 25+ / JDK 17。

## 许可

本项目以 [Apache License 2.0](LICENSE) 开源。

内置第三方组件（详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）：
- [astcenc](https://github.com/ARM-software/astc-encoder) — Apache-2.0（ASTC 编解码）
- [bcdec](https://github.com/iOrange/bcdec) — MIT（BC/DXT 解码）
- [LZ4](https://github.com/lz4/lz4) — BSD-2-Clause（DVPL 压缩）

## 致谢

- DVPL / PVR 格式知识来自 [koreanrandom.com](https://koreanrandom.com) 社区（StranikS_Scan）的逆向文档
