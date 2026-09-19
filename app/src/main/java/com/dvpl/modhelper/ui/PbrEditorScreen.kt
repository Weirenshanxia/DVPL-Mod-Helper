package com.dvpl.modhelper.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dvpl.modhelper.codec.DdsConverter
import com.dvpl.modhelper.codec.DvplCodec
import com.dvpl.modhelper.codec.PbrOps
import com.dvpl.modhelper.codec.PvrConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 编辑操作类型 */
enum class PbrOp(val label: String) {
    COLOR("调色（颜色贴图）"),
    GLOSS("光泽/金属度（RM）"),
    NORMAL("法线生成（颜色图→NM）"),
    CHANNEL("通道工具")
}

/** 导出格式（PVR 系列 + DDS 系列） */
enum class PbrExportFormat(val label: String, val isDds: Boolean, val is4444: Boolean) {
    ASTC_4x4("ASTC 4x4（最高质量）", false, false),
    ASTC_5x5("ASTC 5x5（高质量）", false, false),
    ASTC_6x6("ASTC 6x6（推荐/游戏默认）", false, false),
    ASTC_8x6("ASTC 8x6（中质量）", false, false),
    ASTC_10x5("ASTC 10x5（小体积）", false, false),
    RGBA_8888("不压缩 RGBA8888（无损）", false, false),
    RGBA_4444("RGBA4444（PC DX11 格式）", false, true),
    BC3("BC3 / DXT5（PC 标准）", true, false),
    BC4("BC4（单通道灰度，PC）", true, false),
    BC5("BC5（法线图专用，PC）", true, false);

    fun toAstcQuality(): PvrConverter.AstcQuality = when (this) {
        ASTC_4x4 -> PvrConverter.AstcQuality.ASTC_4x4
        ASTC_5x5 -> PvrConverter.AstcQuality.ASTC_5x5
        ASTC_6x6 -> PvrConverter.AstcQuality.ASTC_6x6
        ASTC_8x6 -> PvrConverter.AstcQuality.ASTC_8x6
        ASTC_10x5 -> PvrConverter.AstcQuality.ASTC_10x5
        RGBA_8888 -> PvrConverter.AstcQuality.RGBA_8888
        RGBA_4444 -> PvrConverter.AstcQuality.RGBA_4444_PC
        else -> PvrConverter.AstcQuality.ASTC_6x6
    }

    fun toDdsFormat(): DdsConverter.DdsFormat = when (this) {
        BC4 -> DdsConverter.DdsFormat.BC4
        BC5 -> DdsConverter.DdsFormat.BC5
        else -> DdsConverter.DdsFormat.BC3
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PbrEditorScreen(
    fileUri: Uri,
    fileName: String,
    outputDirUri: Uri?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ===== 源数据（编辑期只保留降采样预览，导出时重新解码全分辨率）=====
    var previewSrc by remember { mutableStateOf<Bitmap?>(null) }
    var infoText by remember { mutableStateOf("解码中...") }
    var error by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    // ===== 操作参数 =====
    var op by rememberSaveable { mutableStateOf(PbrOp.COLOR) }
    var hue by rememberSaveable { mutableStateOf(0) }
    var sat by rememberSaveable { mutableStateOf(100) }
    var bright by rememberSaveable { mutableStateOf(0) }
    var gloss by rememberSaveable { mutableStateOf(0) }
    var metal by rememberSaveable { mutableStateOf(0) }
    var strength by rememberSaveable { mutableStateOf(100) }
    var invertBump by rememberSaveable { mutableStateOf(false) }
    var swapPair by rememberSaveable { mutableStateOf("") }
    var invertR by rememberSaveable { mutableStateOf(false) }
    var invertG by rememberSaveable { mutableStateOf(false) }
    var invertB by rememberSaveable { mutableStateOf(false) }
    var invertA by rememberSaveable { mutableStateOf(false) }
    var extractCh by rememberSaveable { mutableStateOf("") }

    var showOriginal by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }

    // ===== 解码（降采样预览）=====
    LaunchedEffect(fileUri) {
        withContext(Dispatchers.IO) {
            try {
                val raw = context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取文件")
                val data = if (DvplCodec.isDvplFile(raw))
                    try { DvplCodec.decode(raw) } catch (e: Exception) { raw }
                else raw

                var bmp: Bitmap? = null
                if (PvrConverter.isPvrFile(data)) {
                    bmp = PvrConverter.decodeToBitmap(data)
                } else if (DdsConverter.isDdsFile(data)) {
                    bmp = DdsConverter.decodeToBitmap(data)?.first
                } else if (data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte()) {
                    bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                }
                if (bmp == null) throw IllegalArgumentException("无法解码（支持 PVR/DDS/PNG）")
                // 降采样预览（≤1024）
                val maxSide = 1024
                val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
                val pw = maxOf(1, (bmp.width * scale).toInt())
                val ph = maxOf(1, (bmp.height * scale).toInt())
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, pw, ph, true) else bmp
                previewSrc = scaled
                infoText = fileName + "  " + bmp.width + "x" + bmp.height +
                    (if (scale < 1f) "（预览已缩放，导出为原始分辨率）" else "")
                if (bmp !== scaled) bmp.recycle()
            } catch (e: Exception) {
                error = e.message ?: "解码失败"
            }
        }
    }

    // ===== 实时预览运算 =====
    val previewOut = remember(
        previewSrc, op, hue, sat, bright, gloss, metal, strength, invertBump,
        swapPair, invertR, invertG, invertB, invertA, extractCh
    ) {
        val src = previewSrc ?: return@remember null
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        val out = when (op) {
            PbrOp.COLOR -> PbrOps.adjustColor(px, hue, sat, bright)
            PbrOp.GLOSS -> PbrOps.adjustRm(px, gloss, metal)
            PbrOp.NORMAL -> PbrOps.generateNormal(px, src.width, src.height, strength, invertBump)
            PbrOp.CHANNEL -> PbrOps.channelOps(
                px,
                swap = if (swapPair.length == 2) Pair(swapPair[0], swapPair[1]) else null,
                invertSet = buildSet {
                    if (invertR) add('R'); if (invertG) add('G')
                    if (invertB) add('B'); if (invertA) add('A')
                },
                extract = if (extractCh.isNotEmpty()) extractCh[0] else null
            )
        }
        Bitmap.createBitmap(out, src.width, src.height, Bitmap.Config.ARGB_8888)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PBR 贴图编辑", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    TextButton(
                        onClick = { showExportDialog = true },
                        enabled = previewSrc != null && !isExporting
                    ) { Text(if (isExporting) "导出中..." else "导出") }
                }
            )
        }
    ) { padding ->
        val contentScroll = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(contentScroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (error != null) {
                Text("出错：" + (error ?: ""), color = MaterialTheme.colorScheme.error)
            } else if (previewSrc == null) {
                Text(infoText)
            } else {
            Text(infoText, style = MaterialTheme.typography.bodySmall)

            val shown = if (showOriginal) previewSrc else previewOut
            shown?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showOriginal, onCheckedChange = { showOriginal = it })
                Text("查看原图")
            }

            Divider()

            Text("编辑操作", style = MaterialTheme.typography.titleSmall)
            PbrOp.values().forEach { o ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = op == o, onClick = { op = o })
                    Text(o.label, modifier = Modifier.padding(start = 8.dp))
                }
            }

            Divider()

            when (op) {
                PbrOp.COLOR -> {
                    Text("色相偏移：" + hue + "°")
                    Slider(value = hue.toFloat(), onValueChange = { hue = it.toInt() }, valueRange = -180f..180f)
                    Text("饱和度：" + sat + "%")
                    Slider(value = sat.toFloat(), onValueChange = { sat = it.toInt() }, valueRange = 0f..200f)
                    Text("明度：" + bright)
                    Slider(value = bright.toFloat(), onValueChange = { bright = it.toInt() }, valueRange = -100f..100f)
                }
                PbrOp.GLOSS -> {
                    Text("光泽度：" + gloss + "（正=更亮更光滑）")
                    Slider(value = gloss.toFloat(), onValueChange = { gloss = it.toInt() }, valueRange = -100f..100f)
                    Text("金属感：" + metal)
                    Slider(value = metal.toFloat(), onValueChange = { metal = it.toInt() }, valueRange = -100f..100f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("原厂" to (0 to 0), "哑光" to (-40 to 0), "亮漆" to (70 to 0), "镀铬" to (100 to 100)).forEach { (name, params) ->
                            OutlinedButton(onClick = { gloss = params.first; metal = params.second }) { Text(name) }
                        }
                    }
                    Text(
                        "RM 布局：RGB=粗糙度、A=金属度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PbrOp.NORMAL -> {
                    Text("强度：" + strength + "%")
                    Slider(value = strength.toFloat(), onValueChange = { strength = it.toInt() }, valueRange = 10f..200f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = invertBump, onCheckedChange = { invertBump = it })
                        Text("反转凹凸")
                    }
                    Text(
                        "从颜色图亮度生成法线（Sobel）。暂时仅支持PC端法线贴图",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PbrOp.CHANNEL -> {
                    Text("交换通道", style = MaterialTheme.typography.titleSmall)
                    val swaps = listOf("RG", "RB", "GB", "RA", "GA", "BA")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(selected = swapPair.isEmpty(), onClick = { swapPair = "" }, label = { Text("不交换") })
                        swaps.forEach { s ->
                            FilterChip(selected = swapPair == s, onClick = { swapPair = s }, label = { Text(s) })
                        }
                    }
                    Text("反转通道", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = invertR, onClick = { invertR = !invertR }, label = { Text("反R") })
                        FilterChip(selected = invertG, onClick = { invertG = !invertG }, label = { Text("反G") })
                        FilterChip(selected = invertB, onClick = { invertB = !invertB }, label = { Text("反B") })
                        FilterChip(selected = invertA, onClick = { invertA = !invertA }, label = { Text("反A") })
                    }
                    Text("提取通道为灰度", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = extractCh.isEmpty(), onClick = { extractCh = "" }, label = { Text("不提取") })
                        listOf("R", "G", "B", "A").forEach { c ->
                            FilterChip(selected = extractCh == c, onClick = { extractCh = c }, label = { Text("取" + c) })
                        }
                    }
                }
            }

            if (exportMessage != null) {
                Text(exportMessage!!, style = MaterialTheme.typography.bodySmall)
            }
            } // else（内容主体）
        }
    }

    // ===== 导出对话框 =====
    if (showExportDialog) {
        var fmt by remember { mutableStateOf(PbrExportFormat.ASTC_6x6) }
        var linear by remember(op) { mutableStateOf(op != PbrOp.COLOR) }
        AlertDialog(
            onDismissRequest = { if (!isExporting) showExportDialog = false },
            title = { Text("导出") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("输出格式", style = MaterialTheme.typography.titleSmall)
                    PbrExportFormat.values().forEach { f ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = fmt == f, onClick = { fmt = f })
                            Text(f.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("色彩空间", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !linear, onClick = { linear = false })
                        Text("颜色贴图（sRGB）", modifier = Modifier.padding(start = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = linear, onClick = { linear = true })
                        Text("数据贴图（线性）", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        isExporting = true
                        exportMessage = null
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) {
                                try {
                                    exportFullRes(context, fileUri, fileName, outputDirUri, op, fmt, linear,
                                        hue, sat, bright, gloss, metal, strength, invertBump,
                                        if (swapPair.length == 2) Pair(swapPair[0], swapPair[1]) else null,
                                        buildSet {
                                            if (invertR) add('R'); if (invertG) add('G')
                                            if (invertB) add('B'); if (invertA) add('A')
                                        },
                                        if (extractCh.isNotEmpty()) extractCh[0] else null)
                                } catch (e: Exception) {
                                    "导出失败：" + (e.message ?: "未知错误")
                                } finally {
                                    isExporting = false
                                }
                            }
                            exportMessage = if (msg.startsWith("导出失败")) msg else "已导出：" + msg
                        }
                    },
                    enabled = !isExporting
                ) { Text("确认导出") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 全分辨率导出：重新解码 → 应用运算 → 编码为游戏格式 → SAF 写入 */
private fun exportFullRes(
    context: android.content.Context,
    fileUri: Uri, fileName: String, outputDirUri: Uri?,
    op: PbrOp, fmt: PbrExportFormat, linear: Boolean,
    hue: Int, sat: Int, bright: Int,
    gloss: Int, metal: Int,
    strength: Int, invertBump: Boolean,
    swap: Pair<Char, Char>?, invertSet: Set<Char>, extract: Char?
): String {
    val raw = context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
        ?: throw IllegalStateException("无法读取文件")
    val data = if (DvplCodec.isDvplFile(raw))
        try { DvplCodec.decode(raw) } catch (e: Exception) { raw }
    else raw

    var bmp: Bitmap? = null
    if (PvrConverter.isPvrFile(data)) bmp = PvrConverter.decodeToBitmap(data)
    else if (DdsConverter.isDdsFile(data)) bmp = DdsConverter.decodeToBitmap(data)?.first
    else if (data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte())
        bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
    if (bmp == null) throw IllegalArgumentException("重新解码失败")

    try {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = when (op) {
            PbrOp.COLOR -> PbrOps.adjustColor(px, hue, sat, bright)
            PbrOp.GLOSS -> PbrOps.adjustRm(px, gloss, metal)
            PbrOp.NORMAL -> PbrOps.generateNormal(px, w, h, strength, invertBump)
            PbrOp.CHANNEL -> PbrOps.channelOps(px, swap, invertSet, extract)
        }
        val outBmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        try {
            val encoded = if (fmt.isDds) DdsConverter.encodeToDds(outBmp, fmt.toDdsFormat(), linear)
            else PvrConverter.encodeToPvr(outBmp, fmt.toAstcQuality(), linear)

            // 输出文件名：剥 dvpl/旧扩展/旧格式标签，法线生成换 _NM 类型标签
            val tagExt = when {
                fmt.isDds -> ".dx11.dds"
                fmt.is4444 -> ".dx11.pvr"
                else -> ".astc.pvr"
            }
            var name = fileName
            if (name.endsWith(".dvpl", true)) name = name.dropLast(5)
            val dot = name.lastIndexOf('.')
            if (dot > 0) name = name.substring(0, dot)
            val lower = name.lowercase()
            if (lower.endsWith(".astc") || lower.endsWith(".dx11")) name = name.dropLast(5)
            val outName = if (op == PbrOp.NORMAL) {
                val l2 = name.lowercase()
                val cut = when {
                    l2.endsWith("_bc") -> 3
                    l2.endsWith("_albedo") -> 7
                    l2.endsWith("_base_color") -> 11
                    else -> 0
                }
                if (cut > 0) name.dropLast(cut) + "_NM" else name + "_NM"
            } else name
            val finalName = outName + tagExt

            // SAF 写入
            val dir = outputDirUri
                ?: throw IllegalStateException("请先在主界面设置导出目录")
            val docUri = android.provider.DocumentsContract.createDocument(
                context.contentResolver, dir, "application/octet-stream", finalName
            ) ?: throw IllegalStateException("创建文件失败（目录不可写？）")
            context.contentResolver.openOutputStream(docUri, "wt")?.use { os ->
                os.write(encoded); os.flush()
            } ?: throw IllegalStateException("打开输出流失败")
            return finalName
        } finally {
            outBmp.recycle()
        }
    } finally {
        bmp.recycle()
    }
}
