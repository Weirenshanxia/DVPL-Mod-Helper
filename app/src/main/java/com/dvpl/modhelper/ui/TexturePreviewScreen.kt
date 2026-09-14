package com.dvpl.modhelper.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dvpl.modhelper.codec.DdsConverter
import com.dvpl.modhelper.codec.DvplCodec
import com.dvpl.modhelper.codec.PvrConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TexturePreviewScreen(
    fileUri: Uri,
    fileName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<TextureSource?>(null) }
    var infoText by remember { mutableStateOf("解析中...") }
    var exportBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var mipCount by remember { mutableIntStateOf(1) }
    var currentMip by remember { mutableIntStateOf(0) }
    var glViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }
    var rendererRef by remember { mutableStateOf<TextureRenderer?>(null) }
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }

    // 解析文件
    LaunchedEffect(fileUri) {
        withContext(Dispatchers.IO) {
            try {
                val raw = context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取文件")
                // .pvr.dvpl / .dds.dvpl 自动解包（预览入口统一处理）
                val data = if (DvplCodec.isDvplFile(raw))
                    try { DvplCodec.decode(raw) } catch (e: Exception) { raw }
                else raw
                fileBytes = data
                if (PvrConverter.isPvrFile(data)) {
                    val info = PvrConverter.parse(data)
                        ?: throw IllegalArgumentException("PVR 头解析失败")
                    mipCount = info.mips
                    if (info.isAstc) {
                        // ASTC 压缩数据 GPU 直传
                        val mips = mutableListOf<TextureSource.AstcCompressed.MipData>()
                        var w = info.width; var h = info.height
                        var offset = info.dataOffset.toLong()
                        repeat(info.mips) {
                            val bw = info.blockW; val bh = info.blockH
                            val size = ((w + bw - 1) / bw) * ((h + bh - 1) / bh) * 16
                            val mipBytes = data.copyOfRange(offset.toInt(), (offset + size).toInt())
                            mips.add(TextureSource.AstcCompressed.MipData(mipBytes, w, h))
                            offset += size
                            w = maxOf(1, (w + 1) / 2); h = maxOf(1, (h + 1) / 2)
                        }
                        source = TextureSource.AstcCompressed(
                            info.width, info.height, info.blockW, info.blockH, false, mips)
                        infoText = "PVR ASTC ${info.blockW}x${info.blockH}  ${info.width}x${info.height}  ${info.mips} mips"
                    } else {
                        // 未压缩：软解 mip0
                        val bmp = PvrConverter.decodeToBitmap(data)
                            ?: throw IllegalArgumentException("未压缩 PVR 解码失败")
                        source = TextureSource.DecodedBitmaps(listOf(bmp))
                        infoText = "PVR 未压缩  ${info.width}x${info.height}  ${info.mips} mips"
                        exportBitmap = bmp // P6 优化：保留解码结果，导出时免二次解码
                        return@withContext
                    }
                } else if (DdsConverter.isDdsFile(data)) {
                    val (bmp, format) = DdsConverter.decodeToBitmap(data)
                        ?: throw IllegalArgumentException("DDS 解码失败（格式不支持）")
                    source = TextureSource.DecodedBitmaps(listOf(bmp))
                    infoText = "DDS ${DdsConverter.formatName(format)}  ${bmp.width}x${bmp.height}"
                    exportBitmap = bmp
                } else {
                    throw IllegalArgumentException("不支持的文件（仅 PVR/DDS）")
                }
            } catch (e: Exception) {
                error = e.message ?: "解析失败"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("纹理预览", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = {
                        // 导出 PNG（ASTC 需软解 mip0）
                        scope.launch {
                            val message = withContext(Dispatchers.IO) {
                                try {
                                    val bmp = exportBitmap ?: fileBytes?.let {
                                        if (PvrConverter.isPvrFile(it)) PvrConverter.decodeToBitmap(it) else null
                                    }
                                    if (bmp != null) {
                                        val out = ByteArrayOutputStream()
                                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                        val name = fileName.substringBeforeLast(".") + "_preview.png"
                                        savePng(context, out.toByteArray(), name)
                                    } else {
                                        "导出失败：无法解码"
                                    }
                                } catch (e: Exception) {
                                    "导出失败: ${e.message}"
                                }
                            }
                            // C2 修复：Toast 在主线程
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Default.Download, "导出 PNG") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("错误：$error", color = MaterialTheme.colorScheme.error)
                    }
                }
                source == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    var zoom by remember { mutableFloatStateOf(1f) }
                    var panX by remember { mutableFloatStateOf(0f) }
                    var panY by remember { mutableFloatStateOf(0f) }
                    val src = source!!
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    zoom = (zoom * gestureZoom).coerceIn(0.1f, 40f)
                                    panX += pan.x / 400f / zoom
                                    panY -= pan.y / 400f / zoom
                                    rendererRef?.let { it.zoom = zoom; it.panX = panX; it.panY = panY }
                                    glViewRef?.requestRender()
                                }
                            }
                    ) {
                        LaunchedEffect(source) {
                            rendererRef?.let {
                                it.source = source!!
                                it.markDirty()
                            }
                            glViewRef?.requestRender()
                        }
                        AndroidView(
                            factory = { ctx ->
                                GLSurfaceView(ctx).apply {
                                    setEGLContextClientVersion(3)
                                    val renderer = TextureRenderer(src)
                                    // P3 优化：按需渲染（静止时不耗电）
                                    setRenderer(renderer)
                                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                                    // F2 修复：GPU 直传失败 → 软解回退
                                    renderer.onUploadFailed = {
                                        scope.launch {
                                            val soft = withContext(Dispatchers.IO) {
                                                try {
                                                    fileBytes?.let { PvrConverter.decodeToBitmap(it) }
                                                } catch (e: Exception) { null
                                                }
                                            }
                                            if (soft != null) {
                                                source = TextureSource.DecodedBitmaps(listOf(soft))
                                                exportBitmap = soft
                                                infoText += "（GPU 不支持此格式，已软解回退）"
                                            }
                                        }
                                    }
                                    glViewRef = this
                                    rendererRef = renderer
                                }
                            },
                            update = { _ -> }
                        )
                        // P3 优化：生命周期转发（组合销毁时暂停 GL 线程）
                        DisposableEffect(Unit) {
                            onDispose { glViewRef?.onPause() }
                        }
                    }
                    // 信息栏 + mip 切换
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(infoText, style = MaterialTheme.typography.bodySmall)
                        if (mipCount > 1) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Mip ", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = currentMip.toFloat(),
                                    onValueChange = {
                                        currentMip = it.toInt()
                                        rendererRef?.let { r ->
                                            r.currentMip = currentMip
                                            r.markDirty()
                                        }
                                        glViewRef?.requestRender()
                                    },
                                    valueRange = 0f..(mipCount - 1).toFloat(),
                                    steps = (mipCount - 2).coerceAtLeast(0),
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                )
                                Text("$currentMip", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            "双指缩放/拖动查看  （GPU 直传，无临时文件）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private suspend fun savePng(context: android.content.Context, pngData: ByteArray, fileName: String): String =
    withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/DVPLModHelper")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext "导出失败：无法创建下载记录"
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pngData) }
                    ?: throw IllegalStateException("写入失败")
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                "已导出到 Download/DVPLModHelper"
            } catch (e: Exception) {
                // F4 修复：失败删除 MediaStore 残留记录
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        } catch (e: Exception) {
            "导出失败: ${e.message}"
        }
    }