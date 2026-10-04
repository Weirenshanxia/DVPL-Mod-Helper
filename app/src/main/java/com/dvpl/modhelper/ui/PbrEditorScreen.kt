package com.dvpl.modhelper.ui

import android.graphics.Bitmap
import com.dvpl.modhelper.L
import com.dvpl.modhelper.R
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
enum class PbrOp(@androidx.annotation.StringRes val labelRes: Int) {
    COLOR(R.string.pbr_color),
    GLOSS(R.string.pbr_rm),
    NORMAL(R.string.pbr_normal),
    CHANNEL(R.string.pbr_channel)
}

/** 导出格式（PVR 系列 + DDS 系列） */
enum class PbrExportFormat(@androidx.annotation.StringRes val labelRes: Int, val isDds: Boolean, val is4444: Boolean) {
    ASTC_4x4(R.string.astc_4x4, false, false),
    ASTC_5x5(R.string.astc_5x5, false, false),
    ASTC_6x6(R.string.astc_6x6, false, false),
    ASTC_8x6(R.string.astc_8x6, false, false),
    ASTC_10x5(R.string.astc_10x5, false, false),
    RGBA_8888(R.string.fmt_rgba8888, false, false),
    RGBA_4444(R.string.fmt_rgba4444_pc, false, true),
    BC3(R.string.fmt_bc3_pc, true, false),
    BC4(R.string.fmt_bc4_pc, true, false),
    BC5(R.string.fmt_bc5_pc, true, false);

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
    var infoText by remember { mutableStateOf(L.s(R.string.x_decoding)) }
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
                    ?: throw IllegalStateException(L.s(R.string.e_cant_read))
                val data = if (DvplCodec.isDvplFile(raw))
                    try { DvplCodec.decode(raw) } catch (e: Exception) { raw }
                else raw

                var bmp: Bitmap? = null
                if (PvrConverter.isPvrFile(data)) {
                    bmp = PvrConverter.decodeToBitmap(data)
                } else if (DdsConverter.isDdsFile(data)) {
                    bmp = DdsConverter.decodeToBitmap(data)?.first
                } else if (data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte()) {
                    bmp = BitmapFactory.decodeByteArray(data, 0, data.size,
                        BitmapFactory.Options().apply { inPremultiplied = false })
                }
                if (bmp == null) throw IllegalArgumentException(L.s(R.string.e_pbr_formats))
                // 降采样预览（≤1024）
                val maxSide = 1024
                val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
                val pw = maxOf(1, (bmp.width * scale).toInt())
                val ph = maxOf(1, (bmp.height * scale).toInt())
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, pw, ph, true) else bmp
                previewSrc = scaled
                infoText = fileName + "  " + bmp.width + "x" + bmp.height +
                    (if (scale < 1f) L.s(R.string.x_preview_scaled) else "")
                if (bmp !== scaled) bmp.recycle()
            } catch (e: Exception) {
                error = e.message ?: L.s(R.string.x_decode_fail)
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
        // 直通 alpha 位图: 预览/导出与源数据一致, 半透明不被预乘压暗
        val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        b.isPremultiplied = false
        b.setPixels(out, 0, src.width, 0, 0, src.width, src.height)
        b
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(L.s(R.string.l_pbr_title), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, L.s(R.string.b_back)) } },
                actions = {
                    TextButton(
                        onClick = { showExportDialog = true },
                        enabled = previewSrc != null && !isExporting
                    ) { Text(if (isExporting) L.s(R.string.x_exporting) else L.s(R.string.b_export)) }
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
                Text(L.s(R.string.x_error_prefix) + (error ?: ""), color = MaterialTheme.colorScheme.error)
            } else if (previewSrc == null) {
                Text(infoText)
            } else {
            Text(infoText, style = MaterialTheme.typography.bodySmall)

            val shown = if (showOriginal) previewSrc else previewOut
            shown?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = L.s(R.string.b_preview),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showOriginal, onCheckedChange = { showOriginal = it })
                Text(L.s(R.string.b_view_orig))
            }

            Divider()

            Text(L.s(R.string.l_edit_ops), style = MaterialTheme.typography.titleSmall)
            PbrOp.values().forEach { o ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = op == o, onClick = { op = o })
                    Text(L.s(o.labelRes), modifier = Modifier.padding(start = 8.dp))
                }
            }

            Divider()

            when (op) {
                PbrOp.COLOR -> {
                    Text(L.s(R.string.l_hue) + hue + "°")
                    Slider(value = hue.toFloat(), onValueChange = { hue = it.toInt() }, valueRange = -180f..180f)
                    Text(L.s(R.string.l_saturation) + sat + "%")
                    Slider(value = sat.toFloat(), onValueChange = { sat = it.toInt() }, valueRange = 0f..200f)
                    Text(L.s(R.string.l_brightness) + bright)
                    Slider(value = bright.toFloat(), onValueChange = { bright = it.toInt() }, valueRange = -100f..100f)
                }
                PbrOp.GLOSS -> {
                    Text(L.s(R.string.l_gloss) + gloss + L.s(R.string.x_gloss_hint))
                    Slider(value = gloss.toFloat(), onValueChange = { gloss = it.toInt() }, valueRange = -100f..100f)
                    Text(L.s(R.string.l_metal) + metal)
                    Slider(value = metal.toFloat(), onValueChange = { metal = it.toInt() }, valueRange = -100f..100f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(L.s(R.string.gloss_stock) to (0 to 0), L.s(R.string.gloss_matte) to (-40 to 0), L.s(R.string.gloss_gloss) to (70 to 0), L.s(R.string.gloss_chrome) to (100 to 100)).forEach { (name, params) ->
                            OutlinedButton(onClick = { gloss = params.first; metal = params.second }) { Text(name) }
                        }
                    }
                    Text(
                        L.s(R.string.t_rm_layout),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PbrOp.NORMAL -> {
                    Text(L.s(R.string.l_strength) + strength + "%")
                    Slider(value = strength.toFloat(), onValueChange = { strength = it.toInt() }, valueRange = 10f..200f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = invertBump, onCheckedChange = { invertBump = it })
                        Text(L.s(R.string.l_invert_bump))
                    }
                    Text(
                        L.s(R.string.t_normal_gen),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PbrOp.CHANNEL -> {
                    Text(L.s(R.string.l_swap_channels), style = MaterialTheme.typography.titleSmall)
                    val swaps = listOf("RG", "RB", "GB", "RA", "GA", "BA")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(selected = swapPair.isEmpty(), onClick = { swapPair = "" }, label = { Text(L.s(R.string.ch_none)) })
                        swaps.forEach { s ->
                            FilterChip(selected = swapPair == s, onClick = { swapPair = s }, label = { Text(s) })
                        }
                    }
                    Text(L.s(R.string.l_invert_channels), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = invertR, onClick = { invertR = !invertR }, label = { Text(L.s(R.string.ch_inv_r)) })
                        FilterChip(selected = invertG, onClick = { invertG = !invertG }, label = { Text(L.s(R.string.ch_inv_g)) })
                        FilterChip(selected = invertB, onClick = { invertB = !invertB }, label = { Text(L.s(R.string.ch_inv_b)) })
                        FilterChip(selected = invertA, onClick = { invertA = !invertA }, label = { Text(L.s(R.string.ch_inv_a)) })
                    }
                    Text(L.s(R.string.l_extract_gray), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = extractCh.isEmpty(), onClick = { extractCh = "" }, label = { Text(L.s(R.string.ch_no_extract)) })
                        listOf("R", "G", "B", "A").forEach { c ->
                            FilterChip(selected = extractCh == c, onClick = { extractCh = c }, label = { Text(L.s(R.string.ch_pick) + c) })
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
            title = { Text(L.s(R.string.b_export)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(L.s(R.string.l_out_format), style = MaterialTheme.typography.titleSmall)
                    PbrExportFormat.values().forEach { f ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = fmt == f, onClick = { fmt = f })
                            Text(L.s(f.labelRes), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(L.s(R.string.l_colorspace), style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !linear, onClick = { linear = false })
                        Text(L.s(R.string.l_cs_albedo), modifier = Modifier.padding(start = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = linear, onClick = { linear = true })
                        Text(L.s(R.string.l_cs_data), modifier = Modifier.padding(start = 8.dp))
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
                                    L.s(R.string.x_export_fail_prefix) + (e.message ?: L.s(R.string.x_unknown_error))
                                } finally {
                                    isExporting = false
                                }
                            }
                            exportMessage = if (msg.startsWith(L.s(R.string.x_export_fail))) msg else L.s(R.string.x_exported_prefix) + msg
                        }
                    },
                    enabled = !isExporting
                ) { Text(L.s(R.string.b_confirm_export)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text(L.s(R.string.b_cancel)) }
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
        ?: throw IllegalStateException(L.s(R.string.e_cant_read))
    val data = if (DvplCodec.isDvplFile(raw))
        try { DvplCodec.decode(raw) } catch (e: Exception) { raw }
    else raw

    var bmp: Bitmap? = null
    if (PvrConverter.isPvrFile(data)) bmp = PvrConverter.decodeToBitmap(data)
    else if (DdsConverter.isDdsFile(data)) bmp = DdsConverter.decodeToBitmap(data)?.first
    else if (data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte())
        bmp = BitmapFactory.decodeByteArray(data, 0, data.size,
            BitmapFactory.Options().apply { inPremultiplied = false })
    if (bmp == null) throw IllegalArgumentException(L.s(R.string.e_redecode))

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
        // 直通 alpha 位图: 编码进 DDS/PVR 的通道值与运算结果一致
        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.isPremultiplied = false
        outBmp.setPixels(out, 0, w, 0, 0, w, h)
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

            // 保存：优先自定义目录，否则默认下载目录（与批量转换一致）
            return if (outputDirUri != null) {
                com.dvpl.modhelper.saveToDir(context, outputDirUri, finalName, encoded)
            } else {
                com.dvpl.modhelper.saveToDownloads(context, finalName, encoded)
            }
        } finally {
            outBmp.recycle()
        }
    } finally {
        bmp.recycle()
    }
}
