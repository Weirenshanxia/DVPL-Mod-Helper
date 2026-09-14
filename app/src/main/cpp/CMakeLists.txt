cmake_minimum_required(VERSION 3.22.1)
project("dvpl-codec")

set(CMAKE_CXX_STANDARD 17)

# P9 优化：所有目标显式 -O2（默认 debug 变体 -O0，解码慢一个数量级）
add_compile_options(-O2)

# ===== astcenc 源码编译（ARM 官方 ASTC 编解码） =====
# NEON 向量路径（arm64-v8a），无 OpenCL
set(ASTCENC_TARGET astcenc-static)
set(ASTCENC_SOURCES
    astcenc/astcenc_averages_and_directions.cpp
    astcenc/astcenc_block_sizes.cpp
    astcenc/astcenc_color_quantize.cpp
    astcenc/astcenc_color_unquantize.cpp
    astcenc/astcenc_compress_symbolic.cpp
    astcenc/astcenc_compute_variance.cpp
    astcenc/astcenc_decompress_symbolic.cpp
    astcenc/astcenc_diagnostic_trace.cpp
    astcenc/astcenc_entry.cpp
    astcenc/astcenc_find_best_partitioning.cpp
    astcenc/astcenc_ideal_endpoints_and_weights.cpp
    astcenc/astcenc_image.cpp
    astcenc/astcenc_integer_sequence.cpp
    astcenc/astcenc_mathlib.cpp
    astcenc/astcenc_mathlib_softfloat.cpp
    astcenc/astcenc_partition_tables.cpp
    astcenc/astcenc_percentile_tables.cpp
    astcenc/astcenc_pick_best_endpoint_format.cpp
    astcenc/astcenc_quantization.cpp
    astcenc/astcenc_symbolic_physical.cpp
    astcenc/astcenc_weight_align.cpp
    astcenc/astcenc_weight_quant_xfer_tables.cpp
)

add_library(${ASTCENC_TARGET} STATIC ${ASTCENC_SOURCES})

target_include_directories(${ASTCENC_TARGET} PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/astcenc)

# astcenc 编译选项：NEON + 关闭不必要特性
target_compile_definitions(${ASTCENC_TARGET} PRIVATE
    ASTCENC_SSE=0
    ASTCENC_AVX=0
    ASTCENC_POPCNT=0
    ASTCENC_F16C=0
    ASTCENC_NEON=1
    ASTCENC_DIRECTIVE=inline
    ASTCENC_PRINT_INTERVALS=0
    # DIAGNOSTICS 不能定义（=0 也激活 defined 检查 → TraceLog 失败）
)
# NEON intrinsics 路径
target_compile_options(${ASTCENC_TARGET} PRIVATE -O3 -fvisibility=hidden)

# ===== LZ4 静态库 =====
add_library(lz4_static STATIC
    lz4/lz4.c
    lz4/lz4hc.c
)

# ===== 主库：pvr-codec（PVR + ASTC + LZ4 DVPL） =====
add_library(pvr-codec SHARED
    pvr_codec.cpp
    astc_wrapper.cpp
    dds_codec.cpp
    bcdec.c
)

target_include_directories(pvr-codec PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/astcenc
    ${CMAKE_CURRENT_SOURCE_DIR}/lz4
)

# F7 修复：BC4/BC5 精确模式（支持 SNORM）
target_compile_definitions(pvr-codec PRIVATE BCDEC_BC4BC5_PRECISE)

target_link_libraries(pvr-codec
    astcenc-static
    android
    jnigraphics
    log
    z
)

# ===== dvpl-codec（DVPL LZ4 编解码，原有） =====
add_library(dvpl-codec SHARED
    dvpl_codec.cpp
)

target_include_directories(dvpl-codec PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/lz4
)

target_link_libraries(dvpl-codec
    lz4_static
    android
    jnigraphics
    log
    z
)