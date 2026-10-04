# DVPL Mod Helper

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg)](#系统要求)
[![API](https://img.shields.io/badge/API-26%2B-orange.svg)](#系统要求)
[![Architecture](https://img.shields.io/badge/Arch-arm64--v8a-informational.svg)](#系统要求)

**DVPL Mod Helper** 是一款专为《坦克世界：闪击战》（World of Tanks Blitz）Mod 创作者与游戏资源研究人员设计的高性能 Android 端全功能资源处理工具。

本项目消除了移动端 Mod 制作流程中对 PC 端脚本及特定运行时环境的依赖，在移动设备上实现了资源解包、纹理编解码与跨端移植、PBR 贴图调整、3D 模型提取与预览、动画 WebP 制作以及 Wwise 游戏音频套件等全流程本地化作业。

---

## 核心特性

- 📱 **移动端全流程作业**：脱离 PC 工具链束缚，在 Android 平台独立完成资源提取、修改、格式转换与重新封包。
- 📦 **高性能 DVPL 编解码**：基于 C++ LZ4 与 zlib 原生实现，支持全类型压缩解压与 CRC32 严格校验。
- 🎨 **专业级纹理处理与移植**：
  - 支持 Android 端私有 ASTC PVR 纹理全规格编解码，兼容游戏非标准枚举（`pfLo`）。
  - 支持 PC 端 DirectX 11 RGBA4444 及全系列 DDS BC 压缩格式。
  - 提供 PC 与 Android 贴图双向一键跨端移植能力。
- 🖌️ **内置 PBR 贴图编辑器**：支持法线贴图 Sobel 生成、RM 光泽度/金属度映射调整、色彩变换及多通道操作，编辑过程全交互实时预览。
- 📐 **3D 模型提取与 GPU 预览**：
  - 深度解析私有场景几何容器 `.scg`，关联 `.sc2` 场景层级并自动识别车辆配件部件。
  - 联动已安装官方客户端参数索引（`TankParams`），实现精确的部件层级分类与 LOD 过滤。
  - 内置 OpenGL ES 3D 渲染视图，支持手势平移、缩放、旋转及部件 Solo 独立高亮。
- 🔊 **专业级 Wwise 音频套件**：
  - 完整支持 `.pck` / `.bnk` 容器的音频解包与重打包。
  - 结合 `SoundbanksInfo.json` 与 HIRC 结构还原原始音效文件名、目录树及触发事件（Event）。
  - 内置防爆音 WEM 原生播放器；支持外部音频（WAV/MP3/FLAC 等）高质量重采样编码为合规 WEM 格式。
  - 支持成对重构（`.pck` + `.bnk` 同步截断预取流式音频）与全新 ID 冲突规避重建。
- 🎞️ **通用 WebP 与动画混流**：
  - 静态图自动策略压缩（无损/有损体积比对最优选择）。
  - 基于 MediaCodec 视频解码与纯 Kotlin RFC 9649 混流引擎，将短视频或多帧图像转换为动画 WebP。
- ⚡ **轻量纯净与安全设计**：
  - 安装包体积仅约 3 MB，界面采用 Jetpack Compose + Material Design 3 构建。
  - 基于 Android 存储访问框架（SAF），不申请宽泛敏感的外部存储权限。
  - 动态内存预算限流设计，杜绝批量解码高分辨率纹理时的 OOM 风险。
  - 内置多语言即时热切换（简体中文、English、Русский）。

---

## 功能详解

### 1. 📦 DVPL 编解码核心
- **压缩格式支持**：
  - Type 0 (None / 无压缩)
  - Type 1 (LZ4 标准压缩)
  - Type 2 (LZ4_HC 高压缩比)
  - Type 3 (DEFLATE 扩展压缩)
- **实现与容灾**：底层采用 Native C++ 实现，包含纯 Kotlin 解码回退机制。
- **并发与校验**：多任务协程并行处理；解压前后严格校验 CRC32 校验和与原始大小，防止损坏资产进入游戏。

### 2. 🖼️ 纹理转换与跨端移植
- **PVR 处理**：
  - **PVR → PNG**：支持 ASTC 4x4 至 12x12 全块规格（LDR/HDR 自适应与色调映射），支持未压缩 RGBA8888、R8（单通道迷彩遮罩）以及 PC 端 RGBA4444。自适应游戏私有 `pfLo 27-45` 枚举。
  - **PNG → PVR**：支持 ASTC 4x4、5x5、6x6（官方标准平衡档）、8x6、10x5 等多种压缩质量，支持生成 RGBA8888 无损完整 mipmap 链。
- **DDS 处理**：
  - **DDS → PNG**：基于 `bcdec` 实现 BC1 (DXT1)、BC2 (DXT3)、BC3 (DXT5 / DXT5nm 法线还原)、BC4、BC5、BC6H (HDR)、BC7 全格式解码。支持包含 `.dds.dvpl` 双重封装的文件。
  - **PNG → DDS**：支持编码为 BC3（标准颜色与带透明度贴图）、BC4（单通道灰度图）及 BC5（切线空间法线贴图）。
- **跨平台一键互转**：
  - **DDS ↔ PVR**：在 PC 端 BC 格式与 Android 端 ASTC/RGBA 格式间进行一键双向转换，提供色彩空间（sRGB 颜色贴图与 Linear 数据贴图）及 `images_pbr` 灰度单标量法线格式转换选项。
- **GPU 硬件直显预览**：
  - 基于 OpenGL ES 3.0，ASTC 压缩纹理直传 GPU 硬件解码，零临时文件，毫秒级响应。
  - 支持多指手势缩放、自由平移与 mipmap 分级查看，并在不支持硬件解码的设备上自动平滑回退至软件渲染。

### 3. 🖌️ PBR 贴图编辑器
- **实时降采样交互与全分辨率导出**：在手机端低延迟进行参数调整，导出时自动回溯原图进行完整分辨率渲染输出。
- **光泽度与金属度调节（RM 贴图）**：基于游戏贴图通道布局（RGB 灰度=粗糙度，Alpha 通道=金属度）进行独立增益或衰减控制。
- **法线生成器（Normal Generator）**：基于 Sobel 算子从灰度/高度图计算生成切线空间法线贴图，支持调节法线强度与反转凹凸方向。
- **色彩矩阵校正**：针对 BC/CM 彩色贴图提供色相（Hue）、饱和度（Saturation）与明度（Brightness）的仿射矩阵调整。
- **多通道工具**：支持 RGBA 通道任意两两交换、指定通道反转、以及单通道提取为独立灰度图。
- **灵活导出**：可直接将调整结果保存为各类 ASTC 规格的 PVR、PC 端 DDS (BC3/BC4/BC5)、RGBA8888 以及 PC DX11 RGBA4444 格式。

### 4. 📐 3D 模型解析与导出（SCG → OBJ）
- **私有二进制解包**：完整解析《坦克世界：闪击战》场景几何容器 `.scg`（`SCPG` 文件头），提取顶点、法线、UV 坐标及三角面索引。
- **命名与结构关联（.sc2）**：自动关联解析配对的 `.sc2` 二进制场景文件字符串表，恢复几何网格的官方材质与配件命名。
- **客户端参数联动（TankParams）**：
  - 自动识别设备已安装的游戏客户端（国服及国际服各发行包），读取官方内部车辆参数索引（YAML 碰撞盒与车辆 XML 定义）。
  - 结合几何包围盒比对，自动划分车体（Hull）、炮塔（Turret）、主炮（Gun）、行动部分（Chassis）、涂装与外挂件（Skin/Decor）及挂点标记（Marker）。
- **多附件拼接合并**：支持在导出时同时选取车辆基础模型与额外的涂装外挂件 `.scg`，在统一世界坐标系下完成合并导出。
- **自适应 UV 与步长探测**：内置已知顶点格式映射表，对未知新格式具备动态采样探测能力，自动适配 float 与 half-float 精度。
- **过滤与导出控制**：支持按部件勾选导出、LOD 等级过滤（LOD0 最高细节、LOD0-1 或全量导出）及自动剔除 3 顶点挂点标记。
- **内置 3D GPU 预览（ScgGlView）**：基于 OpenGL ES 2.0，提供真实深度缓冲与光照渲染，支持部件独立高亮（Solo）与半透明幽灵显示。

### 5. 🔊 Wwise 游戏音频套件
- **解包与资产还原**：
  - 从 `.pck` 与 `.bnk` 容器中批量解包提取 `.wem` 音频文件。
  - 结合用户提供的 `SoundbanksInfo.json` 自动解析还原原始层级路径、文件名以及触发事件列表。
- **WEM 原生播放器**：
  - 支持试听由 Vorbis、PCM、PtADPCM、IMA ADPCM 及 Opus 编码的 WEM 文件。
  - 针对游戏高响度音频内置 30% 保护音量，支持音量记忆与暂停控制，联动展示所属 Bank 与关联 Event 名称。
- **格式双向互转**：
  - **WEM → OGG/WAV**：基于 aoTuV 603 码书批量将 WEM 转码还原为标准 Ogg Vorbis 或标准 WAV。
  - **Audio → WEM**：支持将外部常见音频格式（WAV、OGG、MP3、M4A、AAC、FLAC、OPUS）通过 Native libvorbis 编码并封装为游戏合规的标准 WEM 文件（支持 1/2/4 声道）。
- **重构与重打包（Repack）**：
  - 支持选取目标 Bank 与替换用的 WEM 文件进行重打包；支持基于文件夹目录树批量匹配导入。
  - **流式库成对重构**：自动处理闪击战游戏特有的流式音频机制（`.bnk` 中存放前导预取片段，`.pck` 存放完整音频），自动识别并截断预取前导段，实现成对库同步重构。
  - **全新 ID 重建模式**：支持重新分配媒体 ID 并重构 Bank 内联 HIRC 结构，彻底解决多个 Mod 音频库之间的 ID 冲突问题。
  - 严格保持 16 字节对齐布局，未修改资产保持逐字节一致性。

### 6. 🎞️ WebP 通用多媒体处理
- **静态图像转换**：
  - 支持将 PVR、DDS、PNG、JPEG、BMP、GIF 等任意格式图像（含 `.dvpl` 包裹）批量转换为 WebP。
  - 提供 `AUTO` 压缩策略（在 Android 11+ 上并发执行无损与有损编码，自动选取文件体积更小的方案）、有损模式与无损模式。
- **视频转动画 WebP**：
  - 利用系统 `MediaCodec` 硬件解码器解码常见视频文件。
  - 内置纯 Kotlin 实现的 RFC 9649 混流器（`WebpAnimMuxer`），零外部依赖装配 VP8X、ANIM、ANMF 数据块，支持自定义帧率（FPS）与分辨率最长边缩放限制。

---

## 系统要求

- **操作系统**：Android 8.0 (API Level 26) 及以上
- **处理器架构**：`arm64-v8a`（64 位主流移动平台）
- **权限规范**：无需敏感权限，所有文件读写均通过系统 Storage Access Framework (SAF) 授权完成。

---

## 快速上手

1. **选择功能**：在应用主界面中找到对应板块（DVPL 编解码、纹理处理、3D 模型、Wwise 音频或 WebP 转换）。
2. **选取文件**：点击对应按钮调起系统文件选择器，选取需要处理的资源文件（大部分功能支持批量多选）。
3. **参数配置（可选）**：
   - 转换纹理时可根据需要选择输出质量（如 ASTC 6x6 或 BC3）、设定色彩空间模式。
   - 导出 3D 模型时可在弹出对话框中微调部件勾选与 LOD 分级。
   - 打包音频时可在预览对话框中核对匹配结果与替换项。
4. **查看结果**：应用执行完毕后将弹出完成提示，生成的文件保存在指定的导出目录中。
5. **修改导出目录**：默认导出路径为 `Download/DVPLModHelper`，可在主界面顶部点击“更改”自定义持久化存储位置。

> **提示（访问游戏资源文件）**：  
> 由于 Android 11 及更高版本对 `Android/data` 目录施加了严格的作用域存储限制，系统文件选择器默认受限。建议使用支持 SAF 授权或 Root 权限的文件管理器（如 MT 管理器、Material Files）将目标游戏资源复制到公开目录（如 `Download`）后再进行处理。

---

## 技术架构

- **UI 与表现层**：Kotlin、Jetpack Compose、Material Design 3
- **底层 Native 核心（C++ JNI / CMake）**：
  - [astcenc](https://github.com/ARM-software/astc-encoder) — ARM 官方 ASTC 纹理编解码器（NEON SIMD 指令加速）
  - [bcdec](https://github.com/iOrange/bcdec) — 轻量级 DirectDraw Surface (BC1–BC7) 纹理解码器
  - [LZ4](https://github.com/lz4/lz4) — 高速无损压缩与解压缩算法
  - [zlib](https://zlib.net/) — DEFLATE 标准压缩库
  - [libogg](https://xiph.org/ogg/) & [libvorbis](https://xiph.org/vorbis/) — 工业级音频编解码库
  - [ww2ogg](https://github.com/hcs64/ww2ogg) — Wwise Vorbis 逆向解码核心（集成 aoTuV 603 码书）
- **图形管线**：GLSurfaceView、OpenGL ES 2.0 / 3.0
- **多媒体管线**：Android MediaCodec、MediaExtractor、RFC 9649 纯 Kotlin WebP Muxer

---

## 构建指南

本项目建议使用最新版本的 Android Studio 进行编译。详细构建说明请参阅 [BUILD.md](BUILD.md)。

**构建环境要求**：
- Android Studio Ladybug (2024.2.1) 或更高版本
- Android SDK Build-Tools 34.0.0+
- Android NDK 25+
- JDK 17
- CMake 3.22.1+

---

## 开源协议与致谢

本项目采用 [Apache License 2.0](LICENSE) 许可证开源。

- 本项目使用的第三方开源组件及对应许可证声明详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- 特别感谢 koreanrandom.com 社区逆向工程文档（StranikS_Scan 等学者）在 DVPL 与 PVR 格式逆向分析方面做出的贡献。
