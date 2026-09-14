# 第三方组件声明 (Third Party Notices)

本项目包含以下第三方开源组件。各组件版权归属其原作者，按各自许可证条款使用。

---

## astcenc — ARM ASTC Codec (Apache License 2.0)

- **用途**：ASTC 纹理编解码（PVR 转 PNG / PNG 转 PVR）
- **来源**：https://github.com/ARM-software/astc-encoder
- **版权**：Copyright 2011-2026 Arm Limited
- **位置**：`app/src/main/cpp/astcenc/`
- **许可证**：Apache License 2.0（全文见下）

## bcdec (MIT License)

- **用途**：BC1-BC7 (DXT) 纹理解码（DDS 转 PNG）
- **来源**：https://github.com/iOrange/bcdec
- **版权**：Copyright (c) 2022 Sergii "iOrange" Kudlai
- **位置**：`app/src/main/cpp/bcdec.c` / `bcdec.h`
- **许可证**：MIT License（全文见下）

## LZ4 (BSD 2-Clause License)

- **用途**：DVPL 容器的 LZ4 / LZ4_HC 压缩解压
- **来源**：https://github.com/lz4/lz4
- **版权**：Copyright (c) Yann Collet
- **位置**：`app/src/main/cpp/lz4/`
- **许可证**：BSD 2-Clause License（全文见下）

## 其他

- **zlib**：Android NDK 系统库动态链接（`libz.so`），用于 DVPL DEFLATE 类型解码，随系统分发，不包含在本项目中
- **AndroidX / Jetpack Compose / Kotlin 标准库**：Apache License 2.0，通过 Gradle Maven 依赖引入

---

## Apache License 2.0（astcenc）

见本仓库 [LICENSE](LICENSE) 文件（同版本标准全文）。

---

## MIT License（bcdec）

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

## BSD 2-Clause License（LZ4）

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the
     distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
