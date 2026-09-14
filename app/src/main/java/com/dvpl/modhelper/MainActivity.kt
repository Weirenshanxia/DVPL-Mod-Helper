package com.dvpl.modhelper

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.dvpl.modhelper.codec.DdsConverter
import com.dvpl.modhelper.BuildConfig
import com.dvpl.modhelper.codec.DvplCodec
import com.dvpl.modhelper.ui.TexturePreviewScreen
import com.dvpl.modhelper.codec.PvrConverter
import com.dvpl.modhelper.ui.theme.DvplModHelperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// GitHub 仓库地址（开源后替换为实际地址）
private const val GITHUB_URL = "https://github.com/Weirenshanxia/DVPL-Mod-Helper"
private const val ISSUES_URL = "https://github.com/Weirenshanxia/DVPL-Mod-Helper/issues"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DvplModHelperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

/**
 * 转换模式
 */
enum class ConvertMode(val title: String, val subDirName: String) {
    DVPL_DECODE("DVPL 解码", "DVPL解码"),
    DVPL_ENCODE("DVPL 编码", "DVPL编码"),
    PVR_TO_PNG("PVR 转 PNG", "PVR转PNG"),
    PNG_TO_PVR("PNG 转 PVR", "PNG转PVR"),
    DDS_TO_PNG("DDS 转 PNG", "DDS转PNG"),
    PNG_TO_DDS("PNG 转 DDS", "PNG转DDS"),
    DDS_TO_PVR("DDS 转 PVR", "跨端转换"),
    PVR_TO_DDS("PVR 转 DDS", "跨端转换")
}

/**
 * 偏好设置工具（保存导出目录）
 */
object Prefs {
    private const val PREFS_NAME = "dvpl_prefs"
    private const val KEY_OUTPUT_DIR = "output_dir_uri"
    private const val KEY_GROUP_BY_TYPE = "group_by_type"

    fun getGroupByType(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GROUP_BY_TYPE, true)

    fun setGroupByType(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_GROUP_BY_TYPE, value).apply()
    }

    fun getOutputDirUri(context: Context): Uri? {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString(KEY_OUTPUT_DIR, null) ?: return null
        return try {
            Uri.parse(str)
        } catch (e: Exception) {
            null
        }
    }

    fun setOutputDirUri(context: Context, uri: Uri?) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (uri != null) putString(KEY_OUTPUT_DIR, uri.toString()) else remove(KEY_OUTPUT_DIR)
            apply()
        }
    }
}

/**
 * 获取目录显示名
 */
private fun getDirDisplayName(context: Context, uri: Uri?): String {
    if (uri == null) return ""
    return try {
        val docFile = DocumentFile.fromTreeUri(context, uri)
        docFile?.name ?: uri.lastPathSegment ?: "自定义目录"
    } catch (e: Exception) {
        uri.lastPathSegment ?: "自定义目录"
    }
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // C3 修复：关键状态 rememberSaveable（旋转/重建不丢失，防重复任务并发竞争）
    var isProcessing by rememberSaveable { mutableStateOf(false) }
    var processingStatus by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var pendingMode by rememberSaveable { mutableStateOf(ConvertMode.DVPL_DECODE) }
    var astcQuality by rememberSaveable { mutableStateOf(PvrConverter.AstcQuality.ASTC_6x6) }
    var groupByType by rememberSaveable { mutableStateOf(Prefs.getGroupByType(context)) }
    // 批量处理明细记录
    var batchRecords by remember { mutableStateOf<List<Triple<String, Boolean, String>>>(emptyList()) }
    var showBatchDetail by remember { mutableStateOf(false) }
    // 滚动位置保持（预览返回不回顶）
    val mainScrollState = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    var ddsFormat by rememberSaveable { mutableStateOf(DdsConverter.DdsFormat.BC3) }
    var showQualityDialog by remember { mutableStateOf(false) }

    // 导出目录状态
    var outputDirUri by remember { mutableStateOf<Uri?>(Prefs.getOutputDirUri(context)) }
    var outputDirName by remember { mutableStateOf("") }
    // P12 优化：SAF 查询移出主线程
    LaunchedEffect(outputDirUri) {
        outputDirName = withContext(Dispatchers.IO) { getDirDisplayName(context, outputDirUri) }
    }

    // 结果提示（批量结果带"明细"动作按钮）
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(resultMessage) {
        resultMessage?.let { msg ->
            val hasRecords = batchRecords.isNotEmpty()
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = if (hasRecords) "明细" else null,
                withDismissAction = true,
                duration = if (hasRecords) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed && hasRecords) {
                showBatchDetail = true
            }
            resultMessage = null
        }
    }

    // ===== 导出目录选择器（SAF，无需任何权限） =====
    val openDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                // 持久化目录访问权限（重启后仍有效）
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 部分设备可能抛异常，已授予的权限仍有效
            }
            Prefs.setOutputDirUri(context, it)
            outputDirUri = it
            // outputDirName 由 LaunchedEffect(outputDirUri) 异步更新
        }
    }

    // ===== 纹理预览 =====
    var previewFile by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    val previewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { previewFile = Pair(it, queryFileName(context, it)) }
    }

    // ===== 统一的 SAF 多文件选择器（系统文件选择器，不调起媒体库） =====
    // 统一批量处理入口（文件选择器和文件夹导入共用）
    fun processFiles(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            scope.launch {
                isProcessing = true
                processingStatus = "正在处理 " + uris.size + " 个文件..."
                try {
                    val dirUri = outputDirUri
                    val mode = pendingMode
                    val quality = astcQuality
                    val ddsFmt = ddsFormat
                    val groupByType = groupByType
                    // P1 优化：并行处理（4 并发，保序）+ C3 修复：协作式取消
                    val results = withContext(Dispatchers.IO) {
                        val dispatcher = Dispatchers.IO.limitedParallelism(4)
                        kotlinx.coroutines.coroutineScope {
                        uris.map { uri ->
                            async(dispatcher) {
                            var fName = ""
                            try {
                                currentCoroutineContext().ensureActive() // C3：取消检查
                                val input = context.contentResolver.openInputStream(uri)
                                    ?: throw IllegalStateException("无法读取文件")
                                val inputData = input.use { it.readBytes() }
                                val fileName = queryFileName(context, uri)
                                fName = fileName

                                val outputData: ByteArray
                                val outputName: String
                                when (mode) {
                                    ConvertMode.DVPL_DECODE -> {
                                        outputData = DvplCodec.decode(inputData)
                                        outputName = fileName.removeExt(".dvpl")
                                    }
                                    ConvertMode.DVPL_ENCODE -> {
                                        outputData = DvplCodec.encode(inputData, DvplCodec.COMPRESSION_LZ4_HC)
                                        outputName = fileName + ".dvpl"
                                    }
                                    ConvertMode.PVR_TO_PNG -> {
                                        val bitmap = PvrConverter.decodeToBitmap(inputData)
                                            ?: run {
                                                val info = PvrConverter.parse(inputData)
                                                throw IllegalArgumentException("PVR 解码失败" +
                                                    (info?.let { "（ASTC " + it.blockW + "x" + it.blockH + " " + it.width + "x" + it.height + "，详见日志）" } ?: "（格式不支持）"))
                                            }
                                        val out = java.io.ByteArrayOutputStream()
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                        bitmap.recycle()
                                        outputData = out.toByteArray()
                                        outputName = fileName.removeExt(".pvr") + ".png"
                                    }
                                    ConvertMode.PNG_TO_PVR -> {
                                        if (inputData.size < 8 ||
                                            inputData[0] != 0x89.toByte() || inputData[1] != 0x50.toByte() ||
                                            inputData[2] != 0x4E.toByte() || inputData[3] != 0x47.toByte())
                                            throw IllegalArgumentException("不是有效的 PNG 文件（魔数校验失败）")
                                        // F5 防护：先探边界，超限拒绝（防 16K PNG OOM）
                                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        android.graphics.BitmapFactory.decodeByteArray(inputData, 0, inputData.size, opts)
                                        if (opts.outWidth > 8192 || opts.outHeight > 8192)
                                            throw IllegalArgumentException("图片过大（${opts.outWidth}x${opts.outHeight}，上限 8192）")
                                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(inputData, 0, inputData.size)
                                            ?: throw IllegalArgumentException("无法解码 PNG")
                                        outputData = PvrConverter.encodeToPvr(bitmap, quality)
                                        outputName = fileName.removeExt(".png") + ".pvr"
                                    }
                                    ConvertMode.DDS_TO_PNG -> {
                                        val (bitmap, _) = DdsConverter.decodeToBitmap(inputData)
                                            ?: throw IllegalArgumentException("DDS 解码失败（格式不支持）")
                                        val out = java.io.ByteArrayOutputStream()
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                        bitmap.recycle()
                                        outputData = out.toByteArray()
                                        outputName = fileName.removeExt(".dds") + ".png"
                                    }
                                    ConvertMode.PNG_TO_DDS -> {
                                        if (inputData.size < 8 ||
                                            inputData[0] != 0x89.toByte() || inputData[1] != 0x50.toByte() ||
                                            inputData[2] != 0x4E.toByte() || inputData[3] != 0x47.toByte())
                                            throw IllegalArgumentException("不是有效的 PNG 文件（魔数校验失败）")
                                        // F5 防护：先探边界，超限拒绝（防 16K PNG OOM）
                                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        android.graphics.BitmapFactory.decodeByteArray(inputData, 0, inputData.size, opts)
                                        if (opts.outWidth > 8192 || opts.outHeight > 8192)
                                            throw IllegalArgumentException("图片过大（${opts.outWidth}x${opts.outHeight}，上限 8192）")
                                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(inputData, 0, inputData.size)
                                            ?: throw IllegalArgumentException("无法解码 PNG")
                                        outputData = DdsConverter.encodeToDds(bitmap, ddsFmt)
                                        outputName = fileName.removeExt(".png") + ".dds"
                                    }
                                    ConvertMode.DDS_TO_PVR -> {
                                        val (bitmap, _) = DdsConverter.decodeToBitmap(inputData)
                                            ?: throw IllegalArgumentException("DDS 解码失败")
                                        outputData = PvrConverter.encodeToPvr(bitmap, quality)
                                        outputName = fileName.removeExt(".dds") + ".pvr"
                                    }
                                    ConvertMode.PVR_TO_DDS -> {
                                        val bitmap = PvrConverter.decodeToBitmap(inputData)
                                            ?: throw IllegalArgumentException("PVR 解码失败")
                                        outputData = DdsConverter.encodeToDds(bitmap, ddsFmt)
                                        outputName = fileName.removeExt(".pvr") + ".dds"
                                    }
                                }

                                // 保存：优先自定义目录，否则默认下载目录；按类型分文件夹
                                val subDir = if (groupByType) mode.subDirName else null
                                val savedPath = if (dirUri != null) {
                                    saveToDir(context, dirUri, outputName, outputData, subDir)
                                } else {
                                    saveToDownloads(context, outputName, outputData, subDir)
                                }
                                android.util.Log.i("BatchConvert", "OK: " + fileName + " -> " + savedPath)
                                Triple(fileName, true, savedPath)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                android.util.Log.e("BatchConvert", "FAIL: " + fName, e)
                                Triple(fName, false, e.message ?: e.javaClass.simpleName)
                            }
                            }
                        }
                        }.awaitAll()
                    }
                    val successCount = results.count { it.second }
                    val failCount = results.size - successCount
                    batchRecords = results
                    val firstError = results.firstOrNull { !it.second }?.third
                    resultMessage = if (failCount == 0) {
                        "✅ 成功处理 " + successCount + " 个文件"
                    } else {
                        "⚠️ 成功 " + successCount + " 个，失败 " + failCount + " 个"+
                            (firstError?.let { "：" + it } ?: "")
                    }
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    val multipleFilesLauncher = rememberLauncherForActivityResult(
        // GetMultipleContents（ACTION_GET_CONTENT）：MT 管理器等第三方可接管；SAF 无法被第三方接管
        // SAF OpenMultipleDocuments：实测 GET_CONTENT 在部分 ColorOS 15 上黑屏 ANR（PhotoPicker 路由 bug），SAF 稳定
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) processFiles(uris)
    }

    fun launchPicker(mode: ConvertMode) {
        pendingMode = mode
        // 需要质量选择的模式先弹对话框
        if (mode == ConvertMode.PNG_TO_PVR || mode == ConvertMode.PNG_TO_DDS ||
            mode == ConvertMode.PVR_TO_DDS) {
            showQualityDialog = true
            return
        }
        // PNG_TO_PVR/PNG_TO_DDS/PVR_TO_DDS 均已提前 return 走对话框，直接全类型选择
        multipleFilesLauncher.launch(arrayOf("*/*"))
    }

    // ===== 质量选择对话框 =====
    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = {
                Text(
                    if (pendingMode == ConvertMode.PNG_TO_PVR) "选择 ASTC 压缩质量"
                    else "选择 DDS 压缩格式"
                )
                Text(
                    if (pendingMode == ConvertMode.PVR_TO_DDS) "（将应用于 PVR → DDS 转换）"
                    else ""
                    , style = MaterialTheme.typography.bodySmall
                )
            },
            text = {
                Column {
                    if (pendingMode == ConvertMode.PNG_TO_PVR) {
                        PvrConverter.AstcQuality.values().forEach { q ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = astcQuality == q,
                                    onClick = {
                                        astcQuality = q
                                        showQualityDialog = false
                                        multipleFilesLauncher.launch(arrayOf("*/*"))
                                    // mime 放宽：部分设备对 Download 的 PNG 无 MIME 索引，转换前用魔数校验
                                    }
                                )
                                Text(q.label, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    } else {
                        DdsConverter.DdsFormat.values().forEach { f ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = ddsFormat == f,
                                    onClick = {
                                        ddsFormat = f
                                        showQualityDialog = false
                                        // PNG 已放宽为全类型 + 魔数校验（部分设备 MIME 索引不全）
                                        multipleFilesLauncher.launch(arrayOf("*/*"))
                                    }
                                )
                                Text(f.label, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ===== 批量处理明细对话框 =====
    if (showBatchDetail) {
        AlertDialog(
            onDismissRequest = { showBatchDetail = false },
            title = { Text("处理明细（" + batchRecords.size + " 个）") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(batchRecords) { rec ->
                        val (name, ok, msg) = rec
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (ok) "✅" else "❌")
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!ok) {
                                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBatchDetail = false }) { Text("关闭") }
            }
        )
    }

    if (previewFile != null) {
        val (uri, name) = previewFile!!
        TexturePreviewScreen(
            fileUri = uri,
            fileName = name,
            onBack = { previewFile = null }
        )
        return
    }

        Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DVPL Mod 助手") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(mainScrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                WelcomeCard()

                // ===== 导出目录设置卡片 =====
                SectionCard(title = "导出目录") {
                    Text(
                        text = if (outputDirUri != null) "当前：" + outputDirName else "默认：Download/DVPLModHelper",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { openDirLauncher.launch(null) },
                            modifier = Modifier.weight(1f),
                            enabled = !isProcessing
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("修改目录")
                        }
                        if (outputDirUri != null) {
                            OutlinedButton(
                                onClick = {
                                    // 次要修复：释放持久化授权（Android 11+ 有 512 条上限）
                                    outputDirUri?.let {
                                        try {
                                            context.contentResolver.releasePersistableUriPermission(
                                                it,
                                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                            )
                                        } catch (e: SecurityException) { /* 忽略 */ }
                                    }
                                    Prefs.setOutputDirUri(context, null)
                                    outputDirUri = null
                                    outputDirName = ""
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isProcessing
                            ) {
                                Text("恢复默认")
                            }
                        }
                    }
                    // 导出分类开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("按类型分文件夹", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "结果按转换类型保存到子目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = groupByType,
                            onCheckedChange = {
                                groupByType = it
                                Prefs.setGroupByType(context, it)
                            },
                            enabled = !isProcessing
                        )
                    }
                }

                // ===== DVPL 编解码 =====
                SectionCard(title = "DVPL 编解码") {
                    FunctionButton(
                        text = "解码 DVPL → 原文件（支持多选）",
                        icon = Icons.Default.LockOpen,
                        onClick = { launchPicker(ConvertMode.DVPL_DECODE) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = "编码为 DVPL（支持多选）",
                        icon = Icons.Default.Lock,
                        onClick = { launchPicker(ConvertMode.DVPL_ENCODE) },
                        enabled = !isProcessing
                    )
                }

                // ===== PVR / DDS 图片转换（批量） =====
                SectionCard(title = "纹理转换") {
                    FunctionButton(
                        text = "预览纹理（GPU 直显 PVR/DDS）",
                        icon = Icons.Default.Visibility,
                        onClick = { previewLauncher.launch(arrayOf("*/*")) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = "PVR → PNG（支持批量）",
                        icon = Icons.Default.Image,
                        onClick = { launchPicker(ConvertMode.PVR_TO_PNG) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = "PNG → PVR（选择压缩质量）",
                        icon = Icons.Default.PhotoLibrary,
                        onClick = { launchPicker(ConvertMode.PNG_TO_PVR) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = "DDS → PNG（支持批量）",
                        icon = Icons.Default.Collections,
                        onClick = { launchPicker(ConvertMode.DDS_TO_PNG) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = "PNG → DDS（选择压缩格式）",
                        icon = Icons.Default.SaveAlt,
                        onClick = { launchPicker(ConvertMode.PNG_TO_DDS) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = "DDS → PVR（跨端移植 PC→安卓）",
                        icon = Icons.Default.SwapHoriz,
                        onClick = { launchPicker(ConvertMode.DDS_TO_PVR) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = "PVR → DDS（跨端移植 安卓→PC）",
                        icon = Icons.Default.SwapVert,
                        onClick = { launchPicker(ConvertMode.PVR_TO_DDS) },
                        enabled = !isProcessing
                    )
                }

                // ===== 使用说明 =====
                SectionCard(title = "使用说明") {
                    Text(
                        text = "1. 点击功能按钮，通过系统文件选择器选择文件（支持多选）\n" +
                               "2. 转换结果保存到导出目录（可在上方修改）\n" +
                               "3. 同名文件会自动加编号，不会覆盖",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ===== 关于 =====
                SectionCard(title = "关于") {
                    val context = LocalContext.current
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "DVPL Mod 助手 v" + BuildConfig.VERSION_NAME + "\n为 World of Tanks Blitz 准备的纹理 / DVPL 转换工具",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Divider()
                        Text(
                            text = "开源仓库",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = GITHUB_URL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier.clickable {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                                } catch (e: Exception) {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("GitHub", GITHUB_URL))
                                    Toast.makeText(context, "未找到浏览器，链接已复制", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        TextButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("GitHub", GITHUB_URL))
                            Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("复制链接")
                        }
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
                            } catch (e: Exception) {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("GitHub Issues", ISSUES_URL))
                                Toast.makeText(context, "未找到浏览器，链接已复制", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("问题反馈（GitHub Issues）")
                        }
                        Divider()
                        Text(
                            text = "如果这个工具帮到了您，欢迎给仓库点一个 ⭐ Star，这是持续更新的动力",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "本软件以 Apache License 2.0 开源。\n" +
                                   "内置组件：astcenc（Apache-2.0）、bcdec（MIT）、LZ4（BSD-2-Clause），" +
                                   "详见仓库 THIRD_PARTY_NOTICES.md",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "致谢：DVPL / PVR 格式知识来自 koreanrandom.com 社区（StranikS_Scan）的逆向文档",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 加载中覆盖层
            AnimatedVisibility(
                visible = isProcessing,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(elevation = CardDefaults.cardElevation(8.dp)) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(processingStatus.ifEmpty { "处理中..." })
                        }
                    }
                }
            }
        }
    }
}

private fun String.removeExt(ext: String): String {
    // 大小写不敏感的后缀移除（.DVPL/.Png 等也能正确处理）
    // 支持链式后缀：先剥 .dvpl 包裹（如有）再剥目标后缀（x.pvr.dvpl → x）
    var name = this
    if (name.endsWith(".dvpl", ignoreCase = true)) name = name.dropLast(5)
    return if (name.endsWith(ext, ignoreCase = true)) name.dropLast(ext.length) else name
}
/**
 * 查询 SAF Uri 的文件名
 */
private fun queryFileName(context: Context, uri: Uri): String {
    var name: String? = null
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        } catch (e: Exception) {
            // 忽略查询失败
        }
    }
    if (name == null) {
        name = uri.lastPathSegment ?: "file"
    }
    return name ?: "file"
}

/**
 * 生成不冲突的文件名（自动加编号）
 */
private fun makeUniqueFileName(dir: DocumentFile, baseName: String): String {
    val dotIdx = baseName.lastIndexOf('.')
    val base = if (dotIdx > 0) baseName.substring(0, dotIdx) else baseName
    val ext = if (dotIdx > 0) baseName.substring(dotIdx) else ""
    // P5 优化：一次列目录建集合，O(n) SAF 查询（原实现每个候选名一次查询）
    val existing = dir.listFiles().mapNotNull { it.name }.toHashSet()
    var candidate = baseName
    var i = 1
    while (existing.contains(candidate) && i < 1000) {
        candidate = base + " (" + i + ")" + ext
        i++
    }
    return candidate
}

/**
 * 保存到自定义 SAF 目录
 */
private fun saveToDir(
    context: Context,
    dirUri: Uri,
    fileName: String,
    data: ByteArray,
    subDir: String? = null
): String {
    var dir = DocumentFile.fromTreeUri(context, dirUri)
        ?: throw IllegalStateException("无法访问导出目录，请重新设置")
    if (!dir.isDirectory || !dir.canWrite()) {
        throw IllegalStateException("导出目录不可写，请重新设置")
    }
    if (subDir != null) {
        dir = dir.findFile(subDir)?.takeIf { it.isDirectory } ?: dir.createDirectory(subDir)
            ?: throw IllegalStateException("无法创建分类子目录")
    }
    val uniqueName = makeUniqueFileName(dir, fileName)
    val file = dir.createFile("application/octet-stream", uniqueName)
        ?: throw IllegalStateException("无法在导出目录创建文件")
    context.contentResolver.openOutputStream(file.uri)?.use { it.write(data) }
        ?: throw IllegalStateException("无法写入文件")
    return uniqueName
}

/**
 * 查询 MediaStore Downloads 下指定子目录的现有文件名（用于主动去重）
 */
private fun makeUniqueMediaName(context: Context, fileName: String, subDir: String?): String {
    try {
        val relPath = android.os.Environment.DIRECTORY_DOWNLOADS + "/DVPLModHelper/" + (subDir?.let { it + "/" } ?: "")
        val projection = arrayOf(android.provider.MediaStore.Downloads.DISPLAY_NAME)
        val selection = android.provider.MediaStore.Downloads.RELATIVE_PATH + "=?"
        val existing = HashSet<String>()
        context.contentResolver.query(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, arrayOf(relPath), null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) existing.add(cursor.getString(nameIdx))
        }
        val dotIdx = fileName.lastIndexOf('.')
        val base = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
        val ext = if (dotIdx > 0) fileName.substring(dotIdx) else ""
        var candidate = fileName
        var i = 1
        while (existing.contains(candidate) && i < 1000) {
            candidate = base + " (" + i + ")" + ext
            i++
        }
        return candidate
    } catch (e: Exception) {
        return fileName
    }
}

/**
 * 保存到公共下载目录（MediaStore，Android 10+ 无需权限）
 */
private fun saveToDownloads(
    context: Context,
    fileName: String,
    data: ByteArray,
    subDir: String? = null
): String {
    // F4 修复：不再静默回退私有目录；失败直接抛异常让上层报告
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        // 修复：主动查重生成唯一名（系统自动改名会把编号加到全名末尾，位置错误）
        val uniqueName = makeUniqueMediaName(context, fileName, subDir)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, uniqueName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS + "/DVPLModHelper" + (subDir?.let { "/" + it } ?: ""))
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: throw IllegalStateException("无法创建输出文件")
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                ?: throw IllegalStateException("无法写入文件")
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // 失败清理 MediaStore 残留的 0 字节记录
            context.contentResolver.delete(uri, null, null)
            throw e
        }
        return "Download/DVPLModHelper/" + (subDir?.let { it + "/" } ?: "") + uniqueName
    } else {
        @Suppress("DEPRECATION")
        val dir = android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val subDir = java.io.File(dir, "DVPLModHelper")
        if (!subDir.exists()) subDir.mkdirs()
        var target = java.io.File(subDir, fileName)
        var i = 1
        val dotIdx = fileName.lastIndexOf('.')
        val base = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
        val ext = if (dotIdx > 0) fileName.substring(dotIdx) else ""
        while (target.exists() && i < 1000) {
            target = java.io.File(subDir, base + " (" + i + ")" + ext)
            i++
        }
        target.writeBytes(data)
        return "Download/DVPLModHelper/" + target.name
    }
}

@Composable
fun WelcomeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "DVPL Mod 工具",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "为 World of Tanks Blitz 准备的文件转换工具",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "v" + BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Divider()
            content()
        }
    }
}

@Composable
fun FunctionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}