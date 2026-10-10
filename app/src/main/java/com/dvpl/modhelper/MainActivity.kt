@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dvpl.modhelper

import android.app.Activity
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
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.viewinterop.AndroidView
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
import com.dvpl.modhelper.ui.PbrEditorScreen
import com.dvpl.modhelper.ui.SpriteEditorScreen
import com.dvpl.modhelper.ui.TexturePreviewScreen
import com.dvpl.modhelper.ui.UvViewerScreen
import com.dvpl.modhelper.ui.WemPlayerScreen
import com.dvpl.modhelper.codec.PvrConverter
import com.dvpl.modhelper.codec.ScgConverter
import com.dvpl.modhelper.codec.TankParams
import com.dvpl.modhelper.codec.WebpAnimMuxer
import com.dvpl.modhelper.codec.WebpConverter
import com.dvpl.modhelper.codec.WebpStrategy
import com.dvpl.modhelper.codec.WwiseConverter
import com.dvpl.modhelper.codec.WwiseNative
import java.io.File
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
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(L.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动预热官方参数索引（自检日志：能收录多少辆车）
        lifecycleScope.launch(Dispatchers.IO) {
            val n = try { TankParams.warmUp(applicationContext) } catch (e: Exception) { -1 }
            android.util.Log.i("TankParams", "warmUp indexed=$n")
        }
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
enum class ConvertMode(@androidx.annotation.StringRes val titleRes: Int, val subDirName: String) {
    DVPL_DECODE(R.string.mode_dvpl_decode, "DvplDecode"),
    DVPL_ENCODE(R.string.mode_dvpl_encode, "DvplEncode"),
    PVR_TO_PNG(R.string.mode_pvr_png, "PvrToPng"),
    PNG_TO_PVR(R.string.mode_png_pvr, "PngToPvr"),
    DDS_TO_PNG(R.string.mode_dds_png, "DdsToPng"),
    PNG_TO_DDS(R.string.mode_png_dds, "PngToDds"),
    DDS_TO_PVR(R.string.mode_dds_pvr, "CrossConvert"),
    PVR_TO_DDS(R.string.mode_pvr_dds, "CrossConvert"),
    WWISE_UNPACK(R.string.mode_ww_unpack, "WwiseUnpack"),
    WWISE_PACK(R.string.mode_ww_pack, "WwisePack"),
    WWISE_TO_OGG(R.string.mode_wem_ogg, "WwiseOgg"),
    WWISE_TO_WEM(R.string.mode_audio_wem, "AudioToWem"),
    WWISE_PLAY(R.string.mode_wem_play, "WwisePlay"),
    WWISE_TEMPLATE(R.string.mode_ww_template, "WwiseTemplate"),
    WWISE_PACK_FOLDER(R.string.mode_ww_pack_folder, "WwisePack"),
    SCG_TO_OBJ(R.string.mode_scg_obj, "ScgToObj"),
    TO_WEBP(R.string.mode_to_webp, "ToWebp")
}

/**
 * 偏好设置工具（保存导出目录）
 */
object Prefs {
    private const val PREFS_NAME = "dvpl_prefs"
    private const val KEY_OUTPUT_DIR = "output_dir_uri"
    private const val KEY_GROUP_BY_TYPE = "group_by_type"
    private const val KEY_WEM_VOLUME = "wem_volume"

    fun getGroupByType(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GROUP_BY_TYPE, true)

    /** 试听音量（0..1，游戏音频多为 0dBFS 满幅，默认 30% 防炸麦） */
    fun getWemVolume(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_WEM_VOLUME, 0.3f)

    fun setWemVolume(context: Context, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_WEM_VOLUME, value.coerceIn(0f, 1f)).apply()
    }

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
        docFile?.name ?: uri.lastPathSegment ?: L.s(R.string.l_custom_dir)
    } catch (e: Exception) {
        uri.lastPathSegment ?: L.s(R.string.l_custom_dir)
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
    var currentLang by rememberSaveable { mutableStateOf(L.getLanguage()) }
    var showSettingsAbout by rememberSaveable { mutableStateOf(false) }
    // 批量处理明细记录
    var batchRecords by remember { mutableStateOf<List<Triple<String, Boolean, String>>>(emptyList()) }
    var showBatchDetail by remember { mutableStateOf(false) }

    // ===== Wwise 打包模板 / 文件夹打包 =====
    var templateSel by remember { mutableStateOf<List<Pair<Uri, String>>?>(null) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var templateIncludeAudio by remember { mutableStateOf(false) }
    var packBankSel by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    var packPlans by remember { mutableStateOf<List<PackPlan>>(emptyList()) }
    var showPackPreview by remember { mutableStateOf(false) }

    // ===== SCG -> OBJ 模型导出（先解析 → 部件选择对话框 → 导出）=====
    var scgParsed by remember { mutableStateOf<ScgParsed?>(null) }
    var showScgDialog by remember { mutableStateOf(false) }
    var scgLodMode by rememberSaveable { mutableStateOf(1) }   // LOD 快速预设：1=仅LOD0 2=LOD0-1 0=全部

    // 滚动位置保持（预览返回不回顶）
    val mainScrollState = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    var ddsFormat by rememberSaveable { mutableStateOf(DdsConverter.DdsFormat.BC3) }
    // 色彩空间：true=线性（NM/RM/MISC/MASK 数据贴图），false=sRGB（BC/ALBEDO/CM 颜色贴图）
    var isLinear by rememberSaveable { mutableStateOf(false) }
    // 法线输出 images_pbr 灰度版（游戏 pbr 目录 NM 为灰度单标量格式）
    var nmPbrGray by rememberSaveable { mutableStateOf(false) }
    // WebP 输出设置: 0=自动(无损/有损取更小) 1=有损 2=无损; 质量; 视频帧率/最长边
    var webpStrategyIdx by rememberSaveable { mutableStateOf(0) }
    var webpQuality by rememberSaveable { mutableStateOf(75) }
    var webpFps by rememberSaveable { mutableStateOf(10) }
    var webpMaxSide by rememberSaveable { mutableStateOf(720) }
    // WebP 模式: 先选文件, 再弹专属选项对话框
    var showWebpDialog by remember { mutableStateOf(false) }
    var pendingWebpUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
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
                actionLabel = if (hasRecords) L.s(R.string.l_details) else null,
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

    // ===== PBR 贴图编辑 =====
    var pbrEditFile by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    val pbrEditLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { pbrEditFile = Pair(it, queryFileName(context, it)) }
    }

    // ===== UV 查看器（SCG/OBJ → Blender 风格 UV 图; 多选: scg+sc2+涂装附件） =====
    var uvFiles by remember { mutableStateOf<List<Pair<Uri, String>>?>(null) }
    val uvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty())
            uvFiles = uris.map { Pair(it, queryFileName(context, it)) }
    }

    // ===== 精灵图编辑器（Gfx txt + 图集一起多选导入） =====
    var spriteEditFiles by remember { mutableStateOf<List<Pair<Uri, String>>?>(null) }
    val spriteEditLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty())
            spriteEditFiles = uris.map { Pair(it, queryFileName(context, it)) }
    }

    // ===== SC2 场景编辑器
    Sc2Feature.RegisterLauncher(context, queryFileName = { queryFileName(context, it) })

    // Wwise 按模板打包：先选库文件（走 multipleFilesLauncher），processFiles 拦截后再调这里选文件夹
    val sourceFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val bankSel = packBankSel
        if (bankSel.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isProcessing = true
            processingStatus = L.s(R.string.x_scanning)
            try {
                val jsonUris = bankSel.filter { it.second.contains("soundbanksinfo", true) }.map { it.first }
                val banks = bankSel.filterNot { it.second.contains("soundbanksinfo", true) }
                if (banks.isEmpty()) { resultMessage = L.s(R.string.e_no_pck); return@launch }
                val info = collectSoundbanksInfo(context, jsonUris)
                val plans = buildPackPlans(context, banks, treeUri, info) { msg ->
                    scope.launch { processingStatus = msg }
                }
                packPlans = plans
                showPackPreview = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                resultMessage = e.message ?: e.javaClass.simpleName
            } finally { isProcessing = false }
        }
    }

    // ===== 统一的 SAF 多文件选择器（系统文件选择器，不调起媒体库） =====
    // 统一批量处理入口（文件选择器和文件夹导入共用）
    fun processFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // 新流程拦截：不走通用批量管线
        if (pendingMode == ConvertMode.WWISE_TEMPLATE) {
            templateSel = uris.map { Pair(it, queryFileName(context, it)) }
            templateIncludeAudio = false
            showTemplateDialog = true
            return
        }
        if (pendingMode == ConvertMode.WWISE_PACK_FOLDER) {
            packBankSel = uris.map { Pair(it, queryFileName(context, it)) }
            sourceFolderLauncher.launch(null)
            return
        }
        // SCG 模型导出：第一个 .scg 是本体, 其余 .scg(涂装外挂件/其他 scg)全部拼接进来
        if (pendingMode == ConvertMode.SCG_TO_OBJ) {
            val names = uris.map { Pair(it, queryFileName(context, it)) }
            val scgPick = names.filter { it.second.contains(".scg", true) }
            val sc2Pick = names.filter { it.second.contains(".sc2", true) }
            // 文件名不含 .scg 但只选了一个文件时也照收（部分文件管理器会改名）
            val scgEntry = scgPick.firstOrNull() ?: if (uris.size == 1) names.first() else null
            if (scgEntry == null) {
                resultMessage = L.s(R.string.e_scg_parse)
                return
            }
            val scgUri = scgEntry.first
            val scgName = scgEntry.second
            val sc2Uri = sc2Pick.firstOrNull()?.first
            // 手动多选: 除第一个外全部作为附加件（涂装 3D 件等, 同世界坐标系直接拼接）
            val extraScgs = names.filter { it.second.contains(".scg", true) && it.first != scgUri }
                .map { it.second to it.first }
            scope.launch {
                isProcessing = true
                processingStatus = L.s(R.string.x_processing_n, 1)
                try {
                    scgParsed = withContext(Dispatchers.IO) { parseScgForDialog(context, scgUri, sc2Uri, scgName, extraScgs) }
                    showScgDialog = true
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("ScgConvert", "scg flow failed", e)
                    val loc = e.stackTrace.firstOrNull { it.className.contains("com.dvpl") }
                        ?.let { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber } ?: "?"
                    resultMessage = (e.message ?: e.javaClass.simpleName) + " @" + loc
                } finally { isProcessing = false }
            }
            return
        }
        if (uris.isNotEmpty()) {
            scope.launch {
                isProcessing = true
                processingStatus = L.s(R.string.x_processing_n, uris.size)
                try {
                    val dirUri = outputDirUri
                    val mode = pendingMode
                    val quality = astcQuality
                    val ddsFmt = ddsFormat
                    val groupByType = groupByType
                    val linear = isLinear
                    val nmPbr = nmPbrGray
                    val webpStrategy = WebpStrategy.values()[webpStrategyIdx]
                    val webpQ = webpQuality
                    val webpFpsSel = webpFps
                    val webpMaxSideSel = webpMaxSide
                    // P1 优化：并行处理（4 并发，保序）+ C3 修复：协作式取消
                    val results = withContext(Dispatchers.IO) {
                        if (mode == ConvertMode.WWISE_UNPACK || mode == ConvertMode.WWISE_PACK ||
                            mode == ConvertMode.WWISE_TO_OGG || mode == ConvertMode.WWISE_TO_WEM ||
                            mode == ConvertMode.WWISE_PLAY) {
                            processWwiseBatch(context, uris, mode, dirUri, groupByType)
                        } else {
                        // 纹理解码内存预算限流：每 permit = 10MB，总预算 200MB（20 permits）
                        // DVPL 模式无 Bitmap 分配，直接用并发 4 跑满 IO；纹理模式走信号量动态限流
                        val isBitmapMode = mode == ConvertMode.DDS_TO_PNG || mode == ConvertMode.PNG_TO_DDS ||
                            mode == ConvertMode.DDS_TO_PVR || mode == ConvertMode.PVR_TO_DDS ||
                            mode == ConvertMode.PVR_TO_PNG || mode == ConvertMode.PNG_TO_PVR ||
                            mode == ConvertMode.TO_WEBP
                        // java.util.concurrent.Semaphore 支持 acquire(n)/release(n) 原子操作，用于按 Bitmap 大小限流
                        val bitmapSem = java.util.concurrent.Semaphore(20)
                        val videoSem = java.util.concurrent.Semaphore(1)   // 视频逐帧解码串行（防 OOM）
                        val dispatcher = Dispatchers.IO.limitedParallelism(4)
                        kotlinx.coroutines.coroutineScope {
                        uris.map { uri ->
                            async(dispatcher) {
                            var fName = ""
                            try {
                                currentCoroutineContext().ensureActive() // C3：取消检查
                                val fileName = queryFileName(context, uri)
                                fName = fileName
                                // TO_WEBP 视频走 Uri 直读（MediaMetadataRetriever），不整读进内存
                                val isVideoW = mode == ConvertMode.TO_WEBP && isVideoFile(context, uri, fileName)
                                val inputData = if (isVideoW) ByteArray(0) else {
                                    val input = context.contentResolver.openInputStream(uri)
                                        ?: throw IllegalStateException(L.s(R.string.e_cant_read))
                                    input.use { it.readBytes() }
                                }

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
                                        // 估算 Bitmap 内存（解码前探尺寸），按 10MB/permit 限流
                                        val info0 = PvrConverter.parse(inputData)
                                        val estBytes0 = if (info0 != null) info0.width.toLong() * info0.height * 4 else 16L * 1024 * 1024
                                        val permits0 = maxOf(1, minOf(20, (estBytes0 / (10L * 1024 * 1024)).toInt() + 1))
                                        bitmapSem.acquire(permits0)
                                        var bitmap = try {
                                            PvrConverter.decodeToBitmap(inputData)
                                                ?: run {
                                                    bitmapSem.release(permits0)
                                                    throw IllegalArgumentException(L.s(R.string.e_pvr_decode) +
                                                        (info0?.let { "（ASTC " + it.blockW + "x" + it.blockH + " " + it.width + "x" + it.height + L.s(R.string.x_see_log) } ?: L.s(R.string.x_fmt_unsupported)))
                                                }
                                        } catch (e: Exception) { bitmapSem.release(permits0); throw e }
                                        // 法线贴图: 存储值即标准线性编码(2026-10 重验)；pbr 灰度版(RGB=X,A=Y)还原标准色
                                        if (PvrConverter.isNormalMapName(fileName) && PvrConverter.isPbrGrayNormalMap(bitmap))
                                            bitmap = PvrConverter.fromPbrGrayNormalMap(bitmap)
                                        val out = java.io.ByteArrayOutputStream()
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                        bitmap.recycle()
                                        bitmapSem.release(permits0)
                                        outputData = out.toByteArray()
                                        outputName = fileName.removeExt(".pvr") + ".png"
                                    }
                                    ConvertMode.PNG_TO_PVR -> {
                                        if (inputData.size < 8 ||
                                            inputData[0] != 0x89.toByte() || inputData[1] != 0x50.toByte() ||
                                            inputData[2] != 0x4E.toByte() || inputData[3] != 0x47.toByte())
                                            throw IllegalArgumentException(L.s(R.string.e_png_magic))
                                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        android.graphics.BitmapFactory.decodeByteArray(inputData, 0, inputData.size, opts)
                                        if (opts.outWidth > 8192 || opts.outHeight > 8192)
                                            throw IllegalArgumentException(L.s(R.string.tpl_img_too_big8k, opts.outWidth, opts.outHeight))
                                        val estBytes1 = opts.outWidth.toLong() * opts.outHeight * 4
                                        val permits1 = maxOf(1, minOf(20, (estBytes1 / (10L * 1024 * 1024)).toInt() + 1))
                                        bitmapSem.acquire(permits1)
                                        var bitmap = try {
                                            // inPremultiplied=false: PNG 半透明区域解码为直通 alpha, 编码进 PVR 不被压暗
                                            android.graphics.BitmapFactory.decodeByteArray(inputData, 0, inputData.size,
                                                android.graphics.BitmapFactory.Options().apply { inPremultiplied = false })
                                                ?: throw IllegalArgumentException(L.s(R.string.e_png_decode))
                                        } catch (e: Exception) { bitmapSem.release(permits1); throw e }
                                        // 法线贴图: 存储值即标准法线值(线性)，仅强制线性色彩空间
                                        val nm1 = PvrConverter.isNormalMapName(fileName)
                                        // images_pbr 目录版本: 灰度 RGB=X + A=Y 双标量（实测 IS-3/E-100 track）
                                        if (nm1 && nmPbr) bitmap = PvrConverter.toPbrGrayNormalMap(bitmap)
                                        outputData = try { PvrConverter.encodeToPvr(bitmap, quality, nm1 || linear) } finally { bitmap.recycle(); bitmapSem.release(permits1) }
                                        outputName = retagFileName(fileName, pvrTag(quality))
                                    }
                                    ConvertMode.DDS_TO_PNG -> {
                                        // DDS 头 width@0x10 height@0x0C（标准 DDS header，偏移 128B 后）
                                        val ddsW = if (inputData.size >= 20) (inputData[16].toInt() and 0xFF) or ((inputData[17].toInt() and 0xFF) shl 8) else 4096
                                        val ddsH = if (inputData.size >= 16) (inputData[12].toInt() and 0xFF) or ((inputData[13].toInt() and 0xFF) shl 8) else 4096
                                        val estBytes2 = ddsW.toLong() * ddsH * 4
                                        val permits2 = maxOf(1, minOf(20, (estBytes2 / (10L * 1024 * 1024)).toInt() + 1))
                                        bitmapSem.acquire(permits2)
                                        val (bitmap, _) = try {
                                            DdsConverter.decodeToBitmap(inputData, fileName)
                                                ?: throw IllegalArgumentException(L.s(R.string.e_dds_fmt))
                                        } catch (e: Exception) { bitmapSem.release(permits2); throw e }
                                        val out = java.io.ByteArrayOutputStream()
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                        bitmap.recycle()
                                        bitmapSem.release(permits2)
                                        outputData = out.toByteArray()
                                        outputName = fileName.removeExt(".dds") + ".png"
                                    }
                                    ConvertMode.PNG_TO_DDS -> {
                                        if (inputData.size < 8 ||
                                            inputData[0] != 0x89.toByte() || inputData[1] != 0x50.toByte() ||
                                            inputData[2] != 0x4E.toByte() || inputData[3] != 0x47.toByte())
                                            throw IllegalArgumentException(L.s(R.string.e_png_magic))
                                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        android.graphics.BitmapFactory.decodeByteArray(inputData, 0, inputData.size, opts)
                                        if (opts.outWidth > 8192 || opts.outHeight > 8192)
                                            throw IllegalArgumentException(L.s(R.string.tpl_img_too_big8k, opts.outWidth, opts.outHeight))
                                        val estBytes3 = opts.outWidth.toLong() * opts.outHeight * 4
                                        val permits3 = maxOf(1, minOf(20, (estBytes3 / (10L * 1024 * 1024)).toInt() + 1))
                                        bitmapSem.acquire(permits3)
                                        var bitmap = try {
                                            // inPremultiplied=false: 直通 alpha, 编码进 DDS 不被压暗
                                            android.graphics.BitmapFactory.decodeByteArray(inputData, 0, inputData.size,
                                                android.graphics.BitmapFactory.Options().apply { inPremultiplied = false })
                                                ?: throw IllegalArgumentException(L.s(R.string.e_png_decode))
                                        } catch (e: Exception) { bitmapSem.release(permits3); throw e }
                                        // PC 端 DXT5nm: 标准法线 → R=255,G=Y,B=0,A=X 摆放
                                        if (PvrConverter.isNormalMapName(fileName) && ddsFmt == DdsConverter.DdsFormat.BC3)
                                            bitmap = DdsConverter.swizzleToDxt5nm(bitmap)
                                        outputData = try { DdsConverter.encodeToDds(bitmap, ddsFmt, linear) } finally { bitmap.recycle(); bitmapSem.release(permits3) }
                                        outputName = retagFileName(fileName, ".dx11.dds")
                                    }
                                    ConvertMode.DDS_TO_PVR -> {
                                        val ddsW4 = if (inputData.size >= 20) (inputData[16].toInt() and 0xFF) or ((inputData[17].toInt() and 0xFF) shl 8) else 4096
                                        val ddsH4 = if (inputData.size >= 16) (inputData[12].toInt() and 0xFF) or ((inputData[13].toInt() and 0xFF) shl 8) else 4096
                                        val estBytes4 = ddsW4.toLong() * ddsH4 * 4
                                        val permits4 = maxOf(1, minOf(20, (estBytes4 / (10L * 1024 * 1024)).toInt() + 1))
                                        bitmapSem.acquire(permits4)
                                        var (bitmap, _) = try {
                                            DdsConverter.decodeToBitmap(inputData, fileName)
                                                ?: throw IllegalArgumentException(L.s(R.string.e_dds_decode))
                                        } catch (e: Exception) { bitmapSem.release(permits4); throw e }
                                        // 法线贴图: PC DDS 存储值即标准法线值(线性)，直传 + 强制线性
                                        val nm4 = PvrConverter.isNormalMapName(fileName)
                                        // images_pbr 目录版本: 灰度 RGB=X + A=Y 双标量（实测 IS-3/E-100 track）
                                        if (nm4 && nmPbr) bitmap = PvrConverter.toPbrGrayNormalMap(bitmap)
                                        // 色彩空间自动: 按源 DDS 头/贴图类型（DX10 _SRGB→sRGB；旧式头按 NM/RM→线性、底图→sRGB）
                                        val autoLin4 = DdsConverter.detectDdsLinear(inputData, fileName)
                                        outputData = try { PvrConverter.encodeToPvr(bitmap, quality, nm4 || autoLin4) } finally { bitmap.recycle(); bitmapSem.release(permits4) }
                                        outputName = retagFileName(fileName, pvrTag(quality))
                                    }
                                    ConvertMode.PVR_TO_DDS -> {
                                        val info5 = PvrConverter.parse(inputData)
                                        val estBytes5 = if (info5 != null) info5.width.toLong() * info5.height * 4 else 16L * 1024 * 1024
                                        val permits5 = maxOf(1, minOf(20, (estBytes5 / (10L * 1024 * 1024)).toInt() + 1))
                                        bitmapSem.acquire(permits5)
                                        var bitmap = try {
                                            PvrConverter.decodeToBitmap(inputData)
                                                ?: throw IllegalArgumentException(L.s(R.string.e_pvr_decode))
                                        } catch (e: Exception) { bitmapSem.release(permits5); throw e }
                                        // 法线贴图: 存储值即标准线性编码(2026-10 重验)；pbr 灰度版(RGB=X,A=Y)还原标准色
                                        if (PvrConverter.isNormalMapName(fileName) && PvrConverter.isPbrGrayNormalMap(bitmap))
                                            bitmap = PvrConverter.fromPbrGrayNormalMap(bitmap)
                                        // PC 端 DXT5nm: 标准法线 → R=255,G=Y,B=0,A=X 摆放
                                        if (PvrConverter.isNormalMapName(fileName) && ddsFmt == DdsConverter.DdsFormat.BC3)
                                            bitmap = DdsConverter.swizzleToDxt5nm(bitmap)
                                        // 色彩空间自动: 按源 PVR 头 cs 标志
                                        val autoLin5 = PvrConverter.isLinearPvr(inputData) ?: linear
                                        outputData = try { DdsConverter.encodeToDds(bitmap, ddsFmt, autoLin5) } finally { bitmap.recycle(); bitmapSem.release(permits5) }
                                        outputName = retagFileName(fileName, ".dx11.dds")
                                    }
                                    ConvertMode.TO_WEBP -> {
                                        if (isVideoW) {
                                            // 视频 → 动画 WebP（Uri 直读逐帧解码, videoSem 串行防 OOM）
                                            videoSem.acquire()
                                            try {
                                                val retriever = android.media.MediaMetadataRetriever()
                                                try {
                                                    retriever.setDataSource(context, uri)
                                                    val durMs = retriever.extractMetadata(
                                                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                                                        ?: throw IllegalArgumentException(L.s(R.string.e_video_meta))
                                                    val delayMs = (1000 / webpFpsSel).coerceAtLeast(1)
                                                    val maxFrames = (durMs / delayMs).toInt().coerceAtLeast(1)
                                                    val frames = ArrayList<WebpAnimMuxer.Frame>(minOf(maxFrames, 512))
                                                    var canvasW = 0; var canvasH = 0
                                                    // 帧数据字节预算: 混流需全帧驻留内存, 限总量而非帧数(约等于旧 300 帧上限的典型体积)
                                                    var framesBytes = 0L
                                                    val framesBudget = 32L * 1024 * 1024
                                                    fun addFrame(bmp: android.graphics.Bitmap): Boolean {
                                                        if (canvasW == 0) { canvasW = bmp.width; canvasH = bmp.height }
                                                        val webp = WebpConverter.encodeFrame(bmp, webpQ)
                                                        bmp.recycle()
                                                        framesBytes += webp.size
                                                        frames.add(WebpAnimMuxer.Frame(webp, delayMs))
                                                        return framesBytes < framesBudget
                                                    }
                                                    // 快路径: MediaCodec 顺序硬解抽帧（典型 3~10 倍速），失败回退 retriever
                                                    val fastOk = WebpConverter.extractVideoFramesFast(
                                                        context, uri, webpFpsSel, webpMaxSideSel, maxFrames
                                                    ) { bmp -> addFrame(bmp) }
                                                    if (!fastOk) {
                                                        for (i in 0 until maxFrames) {
                                                            currentCoroutineContext().ensureActive()
                                                            val frame = retriever.getFrameAtTime(
                                                                i.toLong() * delayMs * 1000L,
                                                                android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                                                                ?: break   // 尾部帧解不出 → 用已取到的帧收尾
                                                            var scaled = frame
                                                            val longSide = maxOf(frame.width, frame.height)
                                                            if (webpMaxSideSel in 1 until longSide) {
                                                                val scale = webpMaxSideSel.toFloat() / longSide
                                                                scaled = android.graphics.Bitmap.createScaledBitmap(frame,
                                                                    maxOf(1, (frame.width * scale).toInt()),
                                                                    maxOf(1, (frame.height * scale).toInt()), true)
                                                                frame.recycle()
                                                            }
                                                            if (!addFrame(scaled)) break
                                                        }
                                                    }
                                                    if (frames.isEmpty()) throw IllegalArgumentException(L.s(R.string.e_video_frames))
                                                    outputData = WebpAnimMuxer.mux(frames, canvasW, canvasH)
                                                } finally {
                                                    retriever.release()
                                                }
                                            } finally {
                                                videoSem.release()
                                            }
                                            outputName = retagFileName(fileName, ".webp")
                                        } else {
                                            // 图像 → 静态 WebP: dvpl包裹/PVR/DDS/平台格式; 按解码内存申请 permits
                                            val estBytesW = WebpConverter.estimateDecodeBytes(inputData)
                                            val permitsW = maxOf(1, minOf(20, (estBytesW / (10L * 1024 * 1024)).toInt() + 1))
                                            bitmapSem.acquire(permitsW)
                                            var bitmap = try {
                                                WebpConverter.decodeToBitmap(inputData, fileName)
                                                    ?: throw IllegalArgumentException(L.s(R.string.e_img_decode))
                                            } catch (e: Exception) { bitmapSem.release(permitsW); throw e }
                                            // 法线贴图: pbr 灰度版(RGB=X,A=Y)还原标准色（与 PVR_TO_PNG 一致）
                                            if (PvrConverter.isNormalMapName(fileName) && PvrConverter.isPbrGrayNormalMap(bitmap))
                                                bitmap = PvrConverter.fromPbrGrayNormalMap(bitmap)
                                            outputData = try { WebpConverter.encodeImage(bitmap, webpStrategy, webpQ) }
                                                finally { bitmap.recycle(); bitmapSem.release(permitsW) }
                                            outputName = retagFileName(fileName, ".webp")
                                        }
                                    }
                                    else -> throw IllegalStateException(L.s(R.string.e_internal_wise))
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
                    }
                    val successCount = results.count { it.second }
                    val failCount = results.size - successCount
                    batchRecords = results
                    val firstError = results.firstOrNull { !it.second }?.third
                    resultMessage = if (failCount == 0) {
                        L.s(R.string.x_ok_prefix) + successCount + L.s(R.string.x_files_suffix)
                    } else {
                        L.s(R.string.x_warn_ok_prefix) + successCount + L.s(R.string.x_warn_mid) + failCount + L.s(R.string.x_unit_suffix)+
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
        if (uris.isNotEmpty()) {
            // WebP 模式: 先选文件，再弹专属选项对话框
            if (pendingMode == ConvertMode.TO_WEBP) {
                pendingWebpUris = uris
                showWebpDialog = true
            } else {
                processFiles(uris)
            }
        }
    }

    // ===== WEM 试听（仿纹理预览交互）=====
    var wemPlayFiles by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    val wemPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            wemPlayFiles = uris.map { Pair(it, queryFileName(context, it)) }
        }
    }

    fun launchPicker(mode: ConvertMode) {
        pendingMode = mode
        // 需要质量选择的模式先弹对话框
        if (mode == ConvertMode.PNG_TO_PVR || mode == ConvertMode.PNG_TO_DDS ||
            mode == ConvertMode.PVR_TO_DDS || mode == ConvertMode.DDS_TO_PVR) {
            showQualityDialog = true
            return
        }
        // WEM 试听走预览屏（仿纹理预览交互），不走批量转换
        if (mode == ConvertMode.WWISE_PLAY) {
            wemPlayFiles = emptyList()
            wemPickerLauncher.launch(arrayOf("*/*"))
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
                    if (pendingMode == ConvertMode.PNG_TO_PVR || pendingMode == ConvertMode.DDS_TO_PVR)
                        L.s(R.string.x_pick_astc)
                    else L.s(R.string.x_pick_dds)
                )
                Text(
                    when (pendingMode) {
                        ConvertMode.PVR_TO_DDS -> L.s(R.string.x_applies_pvr_dds)
                        ConvertMode.DDS_TO_PVR -> L.s(R.string.x_applies_dds_pvr)
                        else -> ""
                    }
                    , style = MaterialTheme.typography.bodySmall
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (pendingMode == ConvertMode.PNG_TO_PVR || pendingMode == ConvertMode.DDS_TO_PVR) {
                        PvrConverter.AstcQuality.values().forEach { q ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = astcQuality == q,
                                    onClick = { astcQuality = q }
                                )
                                Text(L.s(q.labelRes), modifier = Modifier.padding(start = 8.dp))
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
                                    onClick = { ddsFormat = f }
                                )
                                Text(L.s(f.labelRes), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    // 色彩空间：DDS↔PVR 自动按源文件识别；PNG 来源无头部信息，手动选
                    androidx.compose.material3.Divider(modifier = Modifier.padding(vertical = 8.dp))
                    if (pendingMode == ConvertMode.DDS_TO_PVR || pendingMode == ConvertMode.PVR_TO_DDS) {
                        Text(L.s(R.string.l_cs_auto), style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 4.dp))
                    } else {
                        Text(L.s(R.string.l_colorspace), style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = !isLinear, onClick = { isLinear = false })
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(L.s(R.string.l_cs_albedo))
                                Text(L.s(R.string.l_cs_albedo_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isLinear, onClick = { isLinear = true })
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(L.s(R.string.l_cs_data))
                                Text(L.s(R.string.l_cs_data_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    // 法线 → images_pbr 灰度版（仅 PVR 输出模式显示）
                    if (pendingMode == ConvertMode.PNG_TO_PVR || pendingMode == ConvertMode.DDS_TO_PVR) {
                        androidx.compose.material3.Divider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = nmPbrGray, onCheckedChange = { nmPbrGray = it })
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(L.s(R.string.nm_pbr_gray))
                                Text(L.s(R.string.nm_pbr_gray_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showQualityDialog = false
                    multipleFilesLauncher.launch(arrayOf("*/*"))
                }) { Text(L.s(R.string.b_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showQualityDialog = false }) { Text(L.s(R.string.b_cancel)) }
            }
        )
    }

    // ===== WebP 转换选项（先选文件后弹，图像/视频混批通用）=====
    if (showWebpDialog) {
        AlertDialog(
            onDismissRequest = { showWebpDialog = false },
            title = { Text(L.s(R.string.webp_opts_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(L.s(R.string.webp_mixed_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 压缩策略
                    Text(L.s(R.string.webp_strategy), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(0 to R.string.webp_strat_auto, 1 to R.string.webp_strat_lossy,
                               2 to R.string.webp_strat_lossless).forEach { (idx, res) ->
                            FilterChip(selected = webpStrategyIdx == idx,
                                onClick = { webpStrategyIdx = idx },
                                label = { Text(L.s(res)) })
                        }
                    }
                    if (webpStrategyIdx == 0)
                        Text(L.s(R.string.webp_strat_auto_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp))
                    // 质量
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(L.s(R.string.webp_quality), style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f))
                        Text(webpQuality.toString() + "%", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(value = webpQuality.toFloat(),
                        onValueChange = { webpQuality = it.toInt().coerceIn(25, 100) },
                        valueRange = 25f..100f, steps = 74)
                    Text(L.s(R.string.webp_quality_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 视频参数（混批时仅对视频文件生效）
                    androidx.compose.material3.Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(L.s(R.string.webp_fps), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(5, 10, 15, 24).forEach { f ->
                            FilterChip(selected = webpFps == f, onClick = { webpFps = f },
                                label = { Text(f.toString() + " fps") })
                        }
                    }
                    Text(L.s(R.string.webp_maxside), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(480, 720, 1080, 0).forEach { s ->
                            FilterChip(selected = webpMaxSide == s, onClick = { webpMaxSide = s },
                                label = { Text(if (s == 0) L.s(R.string.webp_side_orig) else s.toString()) })
                        }
                    }
                    Text(L.s(R.string.webp_video_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showWebpDialog = false
                    processFiles(pendingWebpUris)
                }) { Text(L.s(R.string.b_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showWebpDialog = false }) { Text(L.s(R.string.b_cancel)) }
            }
        )
    }

    // ===== 批量处理明细对话框 =====
    if (showBatchDetail) {
        AlertDialog(
            onDismissRequest = { showBatchDetail = false },
            title = { Text(L.s(R.string.x_detail_prefix) + batchRecords.size + L.s(R.string.x_detail_suffix)) },
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
                TextButton(onClick = { showBatchDetail = false }) { Text(L.s(R.string.b_close)) }
            }
        )
    }

    // ===== 模板导出对话框 =====
    if (showTemplateDialog && templateSel != null) {
        val sel = templateSel!!
        val hasJson = sel.any { it.second.contains("soundbanksinfo", true) }
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text(L.s(R.string.tpl_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sel.joinToString("\n") { it.second },
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(L.s(R.string.tpl_opt_include_audio),
                                style = MaterialTheme.typography.bodyMedium)
                            Text(L.s(R.string.tpl_opt_include_audio_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = templateIncludeAudio,
                            onCheckedChange = { templateIncludeAudio = it }
                        )
                    }
                    if (!hasJson) Text(L.s(R.string.tpl_need_json),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTemplateDialog = false
                    val banks = sel.filterNot { it.second.contains("soundbanksinfo", true) }
                    val jsons = sel.filter { it.second.contains("soundbanksinfo", true) }.map { it.first }
                    val includeAudio = templateIncludeAudio
                    val dirUri = outputDirUri
                    scope.launch {
                        isProcessing = true
                        processingStatus = L.s(R.string.x_template_started)
                        try {
                            val info = collectSoundbanksInfo(context, jsons)
                            val res = exportPackTemplate(context, banks, info, includeAudio, dirUri, "WwisePackTemplate")
                            resultMessage = L.s(R.string.x_tpl_done) +
                                res.joinToString("、") { it.dirName } +
                                res.sumOf { it.folders }.toString() +
                                L.s(R.string.x_tpl_files)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            resultMessage = e.message ?: e.javaClass.simpleName
                        } finally { isProcessing = false }
                    }
                }) { Text(L.s(R.string.b_export_template)) }
            },
            dismissButton = {
                TextButton(onClick = { showTemplateDialog = false }) { Text(L.s(R.string.b_cancel)) }
            }
        )
    }

    // ===== SCG -> OBJ 部件选择对话框（LOD 只是快速预设，配件勾选独立）=====
    if (showScgDialog && scgParsed != null) {
        val parsed = scgParsed!!
        // 勾选状态；LOD 预设/全选/全不选都会整体重置它
        var scgSelected by remember(parsed) {
            mutableStateOf(scgDefaultSelection(parsed.groups, scgLodMode))
        }
        // 长按部件进入单独预览（不改勾选状态），再次长按/点提示条退出
        var scgSoloId by remember(parsed) { mutableStateOf<Long?>(null) }
        AlertDialog(
            onDismissRequest = { showScgDialog = false },
            title = { Text(L.s(R.string.scg_dlg_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(parsed.fileName, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // sc2 配件名状态
                    Text(
                        if (parsed.namesFound) L.s(R.string.scg_names_found) else L.s(R.string.scg_names_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (parsed.namesFound) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(L.s(R.string.scg_lod_label), style = MaterialTheme.typography.labelMedium)
                    listOf(1, 2, 0).forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                scgLodMode = mode
                                scgSelected = scgDefaultSelection(parsed.groups, mode)
                            }
                        ) {
                            RadioButton(selected = scgLodMode == mode, onClick = null)
                            Text(
                                when (mode) {
                                    1 -> L.s(R.string.scg_lod_0)
                                    2 -> L.s(R.string.scg_lod_01)
                                    else -> L.s(R.string.scg_lod_all)
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(L.s(R.string.scg_selected, scgSelected.size, parsed.groups.size),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { scgSelected = parsed.groups.map { it.id }.toSet() }) {
                            Text(L.s(R.string.scg_sel_all)) }
                        TextButton(onClick = { scgSelected = emptySet() }) {
                            Text(L.s(R.string.scg_sel_none)) }
                    }
                    // 3D 预览（OpenGL）：勾选组实心、其余幽灵灰；长按列表项可单独预览
                    Box(Modifier.fillMaxWidth().height(230.dp)) {
                        AndroidView(
                            factory = { ctx -> ScgGlView(ctx).apply { setScene(parsed.groups) } },
                            update = { it.setDisplay(scgSelected, scgSoloId) },
                            onRelease = { it.onPause() },
                            modifier = Modifier.fillMaxSize()
                        )
                        val soloGroup = scgSoloId?.let { id -> parsed.groups.firstOrNull { it.id == id } }
                        if (soloGroup != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clickable { scgSoloId = null }
                            ) {
                                Text(
                                    L.s(R.string.scg_solo_chip, soloGroup.name ?: ("#" + soloGroup.id)),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        } else {
                            Text(L.s(R.string.scg_preview_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            )
                        }
                    }
                    // 配件清单：按类别分组（车体/炮塔/火炮/行走机构…），名字 + 顶点数 + LOD
                    val byCat = parsed.groups.groupBy { scgCategory(it) }.toSortedMap()
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        for ((cat, catGroups) in byCat) {
                            item(key = "cat$cat".hashCode().toLong()) {
                                Text(
                                    L.s(scgCategoryLabel(cat)) + " · " + catGroups.size,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                )
                            }
                            items(catGroups.sortedByDescending { it.vertexCount }, key = { it.id }) { g ->
                                val checked = g.id in scgSelected
                                val soloed = g.id == scgSoloId
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                        .background(
                                            if (soloed) MaterialTheme.colorScheme.primaryContainer
                                            else androidx.compose.ui.graphics.Color.Transparent
                                        )
                                        .combinedClickable(
                                            onClick = {
                                                scgSelected = if (checked) scgSelected - g.id else scgSelected + g.id
                                            },
                                            onLongClick = {
                                                scgSoloId = if (soloed) null else g.id
                                            }
                                        )
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                    Text(
                                        (g.name ?: ("#" + g.id)) +
                                            (if (g.vertexCount <= 3) "  [" + L.s(R.string.scg_marker) + "]" else "  (" + g.vertexCount + "v)") +
                                            (if (g.lod > 0) "  LOD" + g.lod else "") +
                                            (if (soloed) "  ●" else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showScgDialog = false
                    val sel = scgSelected
                    val dirUri = outputDirUri
                    val groupByTypeNow = groupByType
                    scope.launch {
                        isProcessing = true
                        processingStatus = L.s(R.string.x_processing_n, 1)
                        try {
                            resultMessage = processScg(context, parsed, sel, dirUri, groupByTypeNow)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            android.util.Log.e("ScgConvert", "scg export failed", e)
                            val loc = e.stackTrace.firstOrNull { it.className.contains("com.dvpl") }
                                ?.let { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber } ?: "?"
                            resultMessage = (e.message ?: e.javaClass.simpleName) + " @" + loc
                        } finally { isProcessing = false }
                    }
                }) { Text(L.s(R.string.scg_export)) }
            },
            dismissButton = {
                TextButton(onClick = { showScgDialog = false }) { Text(L.s(R.string.b_cancel)) }
            }
        )
    }

    // ===== 打包预览对话框 =====
    if (showPackPreview && packPlans.isNotEmpty()) {
        PackPreviewDialog(
            plans = packPlans,
            onDismiss = { showPackPreview = false; packPlans = emptyList() },
            onConfirm = { confirmed, rebuildNow ->
                showPackPreview = false
                packPlans = emptyList()
                val dirUri = outputDirUri
                val groupByTypeNow = groupByType
                val sub = ConvertMode.WWISE_PACK_FOLDER.subDirName
                scope.launch {
                    isProcessing = true
                    processingStatus = L.s(R.string.x_packing)
                    try {
                        val results = executePackPlans(context, confirmed, dirUri, groupByTypeNow, sub, rebuildNow)
                        batchRecords = results
                        val ok = results.count { it.second }
                        val bad = results.size - ok
                        resultMessage = if (bad == 0)
                            L.s(R.string.x_ok_prefix) + ok + L.s(R.string.x_files_suffix)
                        else
                            L.s(R.string.x_warn_ok_prefix) + ok + L.s(R.string.x_warn_mid) + bad +
                                L.s(R.string.x_files_suffix) +
                                (results.firstOrNull { !it.second }?.third?.let { "：" + it } ?: "")
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        resultMessage = e.message ?: e.javaClass.simpleName
                    } finally { isProcessing = false }
                }
            }
        )
    }

    // UV 查看器屏
    if (uvFiles != null) {
        UvViewerScreen(
            files = uvFiles!!,
            onBack = { uvFiles = null }
        )
        return
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

    // PBR 贴图编辑屏
    if (pbrEditFile != null) {
        val (uri, name) = pbrEditFile!!
        PbrEditorScreen(
            fileUri = uri,
            fileName = name,
            outputDirUri = outputDirUri,
            onBack = { pbrEditFile = null }
        )
        return
    }

    // 精灵图编辑屏（Gfx txt + 图集多选）
    if (spriteEditFiles != null) {
        SpriteEditorScreen(
            files = spriteEditFiles!!,
            outputDirUri = outputDirUri,
            onBack = { spriteEditFiles = null }
        )
        return
    }

    // SC2 场景编辑屏
    if (Sc2Feature.MaybeScreen(outputDirUri)) return

    // WEM 试听屏（多文件列表）
    if (wemPlayFiles.isNotEmpty()) {
        WemPlayerScreen(
            files = wemPlayFiles,
            onBack = { wemPlayFiles = emptyList() }
        )
        return
    }

    // 语言切换时强制整树重组：无参 composable 与单例 lambda 会被跳过重组
    key(currentLang) {
        Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(L.s(R.string.app_title)) },
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
                SectionCard(title = L.s(R.string.l_export_dir)) {
                    Text(
                        text = if (outputDirUri != null) L.s(R.string.l_current) + outputDirName else L.s(R.string.l_default_dir),
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
                            Text(L.s(R.string.b_change_dir))
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
                                Text(L.s(R.string.b_reset_dir))
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
                            Text(L.s(R.string.l_group_by_type), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                L.s(R.string.l_group_by_type_hint),
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
                SectionCard(title = L.s(R.string.sec_dvpl)) {
                    FunctionButton(
                        text = L.s(R.string.b_decode_dvpl),
                        icon = Icons.Default.LockOpen,
                        onClick = { launchPicker(ConvertMode.DVPL_DECODE) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_encode_dvpl),
                        icon = Icons.Default.Lock,
                        onClick = { launchPicker(ConvertMode.DVPL_ENCODE) },
                        enabled = !isProcessing
                    )
                }

                // ===== PVR / DDS 图片转换（批量） =====
                SectionCard(title = L.s(R.string.sec_texture)) {
                    FunctionButton(
                        text = L.s(R.string.b_preview_tex),
                        icon = Icons.Default.Visibility,
                        onClick = { previewLauncher.launch(arrayOf("*/*")) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_pbr_editor),
                        icon = Icons.Default.Tune,
                        onClick = { pbrEditLauncher.launch(arrayOf("*/*")) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_pvr_png),
                        icon = Icons.Default.Image,
                        onClick = { launchPicker(ConvertMode.PVR_TO_PNG) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_png_pvr),
                        icon = Icons.Default.PhotoLibrary,
                        onClick = { launchPicker(ConvertMode.PNG_TO_PVR) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_dds_png),
                        icon = Icons.Default.Collections,
                        onClick = { launchPicker(ConvertMode.DDS_TO_PNG) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_png_dds),
                        icon = Icons.Default.SaveAlt,
                        onClick = { launchPicker(ConvertMode.PNG_TO_DDS) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_dds_pvr),
                        icon = Icons.Default.SwapHoriz,
                        onClick = { launchPicker(ConvertMode.DDS_TO_PVR) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_pvr_dds),
                        icon = Icons.Default.SwapVert,
                        onClick = { launchPicker(ConvertMode.PVR_TO_DDS) },
                        enabled = !isProcessing
                    )
                }

                // ===== 精灵图编辑（texture0+txt） =====
                SectionCard(title = L.s(R.string.sec_texture_txt)) {
                    FunctionButton(
                        text = L.s(R.string.b_sprite_editor),
                        icon = Icons.Default.Crop,
                        onClick = { spriteEditLauncher.launch(arrayOf("*/*")) },
                        enabled = !isProcessing
                    )
                }

                // ===== 3D 模型（SCG -> OBJ，含 LOD 筛选） =====
                SectionCard(title = L.s(R.string.sec_3d)) {
                    FunctionButton(
                        text = L.s(R.string.b_scg_obj),
                        icon = Icons.Default.ViewInAr,
                        onClick = { launchPicker(ConvertMode.SCG_TO_OBJ) },
                        enabled = !isProcessing
                    )

                    FunctionButton(
                        text = L.s(R.string.b_uv_viewer),
                        icon = Icons.Default.GridView,
                        onClick = { uvLauncher.launch(arrayOf("*/*")) },
                        enabled = !isProcessing
                    )

                    Sc2Feature.Button(isProcessing)
                }

                // ===== WebP 通用转换（图像/视频混批，标准格式、体积小） =====
                SectionCard(title = L.s(R.string.sec_webp)) {
                    FunctionButton(
                        text = L.s(R.string.b_to_webp),
                        icon = Icons.Default.Collections,
                        onClick = { launchPicker(ConvertMode.TO_WEBP) },
                        enabled = !isProcessing
                    )
                }

                // ===== Wwise 音频（语音/音效） =====
                SectionCard(title = L.s(R.string.sec_wwise)) {
                    // ── 工具 ──
                    Text(
                        text = L.s(R.string.sec_wwise_tools),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = L.s(R.string.t_wwise_tools_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FunctionButton(
                        text = L.s(R.string.b_ww_unpack),
                        icon = Icons.Default.GraphicEq,
                        onClick = { launchPicker(ConvertMode.WWISE_UNPACK) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_wem_ogg),
                        icon = Icons.Default.AudioFile,
                        onClick = { launchPicker(ConvertMode.WWISE_TO_OGG) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_audio_wem),
                        icon = Icons.Default.Mic,
                        onClick = { launchPicker(ConvertMode.WWISE_TO_WEM) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_wem_play),
                        icon = Icons.Default.PlayCircle,
                        onClick = { launchPicker(ConvertMode.WWISE_PLAY) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_ww_pack),
                        icon = Icons.Default.LibraryMusic,
                        onClick = { launchPicker(ConvertMode.WWISE_PACK) },
                        enabled = !isProcessing
                    )
                    // ── 制作语音 Mod ──
                    Text(
                        text = L.s(R.string.sec_wwise_mod),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = L.s(R.string.t_wwise_mod_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FunctionButton(
                        text = L.s(R.string.b_ww_template),
                        icon = Icons.Default.Description,
                        onClick = { launchPicker(ConvertMode.WWISE_TEMPLATE) },
                        enabled = !isProcessing
                    )
                    FunctionButton(
                        text = L.s(R.string.b_ww_pack_folder),
                        icon = Icons.Default.FolderOpen,
                        onClick = { launchPicker(ConvertMode.WWISE_PACK_FOLDER) },
                        enabled = !isProcessing
                    )
                }

                // ===== 使用说明 =====
                SectionCard(title = L.s(R.string.l_instructions)) {
                    Text(
                        text = L.s(R.string.t_step1) +
                               L.s(R.string.t_step2) +
                               L.s(R.string.t_step3),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ===== 设置与关于 =====
                SectionCard(title = L.s(R.string.l_settings_about)) {
                    FunctionButton(
                        text = L.s(R.string.b_open_settings_about),
                        icon = Icons.Default.Info,
                        onClick = { showSettingsAbout = true },
                        enabled = !isProcessing
                    )
                }
            }


            // 设置与关于对话框
            if (showSettingsAbout) {
                val dialogContext = LocalContext.current
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showSettingsAbout = false },
                    title = { Text(L.s(R.string.l_settings_about)) },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(L.s(R.string.l_language), style = MaterialTheme.typography.titleSmall)
                            for ((code, name) in listOf(
                                "system" to L.s(R.string.lang_system),
                                "zh" to "中文",
                                "en" to "English",
                                "ru" to "Русский"
                            )) {
                                androidx.compose.material3.FilterChip(
                                    selected = currentLang == code,
                                    onClick = {
                                        if (currentLang != code) {
                                            L.applyLanguage(code)
                                            currentLang = code
                                        }
                                    },
                                    label = { Text(name) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Divider()
                            Text(L.s(R.string.l_about), style = MaterialTheme.typography.titleSmall)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                
                                Text(
                                text = L.s(R.string.x_version) + BuildConfig.VERSION_NAME + L.s(R.string.t_tagline),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Divider()
                                Text(
                                text = L.s(R.string.t_wise_tip),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Divider()
                                Text(
                                text = L.s(R.string.l_repo),
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
                                dialogContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                                } catch (e: Exception) {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("GitHub", GITHUB_URL))
                                Toast.makeText(context, L.s(R.string.x_no_browser), Toast.LENGTH_SHORT).show()
                                }
                                }
                                )
                                TextButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("GitHub", GITHUB_URL))
                                Toast.makeText(context, L.s(R.string.x_link_copied), Toast.LENGTH_SHORT).show()
                                }) {
                                Text(L.s(R.string.b_copy_link))
                                }
                                TextButton(onClick = {
                                try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
                                } catch (e: Exception) {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("GitHub Issues", ISSUES_URL))
                                Toast.makeText(context, L.s(R.string.x_no_browser), Toast.LENGTH_SHORT).show()
                                }
                                }) {
                                Text(L.s(R.string.l_feedback))
                                }
                                Divider()
                                Text(
                                text = L.s(R.string.t_star),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                text = L.s(R.string.t_license) +
                                L.s(R.string.t_oss_1) +
                                L.s(R.string.t_oss_2),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                text = L.s(R.string.t_credits),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showSettingsAbout = false }) {
                            Text(L.s(R.string.b_close))
                        }
                    }
                )
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
                            Text(processingStatus.ifEmpty { L.s(R.string.x_processing) })
                        }
                    }
                }
            }
        }
    }
    }  // key(currentLang)
}

/** 解析结果：文件名 + 全部组（已命名、已分级 LOD），供部件选择对话框使用 */
class ScgParsed(
    val fileName: String,
    val scgUri: Uri,
    val namesFound: Boolean,
    val groups: List<ScgConverter.ScgGroup>
)

/** 配件分类（按名字+顶点数）：0车体 1炮塔 2火炮 3行走机构 4皮肤/贴花 5装饰 6其他 7挂点标记 */
private fun scgCategory(g: ScgConverter.ScgGroup): Int {
    val n = (g.name ?: "").lowercase()
    return when {
        g.vertexCount <= 3 -> 7                       // 3 顶点小三角 = 挂点标记
        n.contains("turret") -> 1
        n.contains("gun") -> 2
        n.contains("hull") -> 0
        n.contains("chassis") || n.contains("track") || n.contains("side_") ||
            n.contains("wheel") || n.contains("exhaus") -> 3
        n.contains("skin") || n.contains("decal") -> 4
        n.contains("ny") || n.contains("hw") || n.contains("pumpkin") ||
            n.contains("ladder") || n.contains("cinema") -> 5
        else -> 6
    }
}

private fun scgCategoryLabel(cat: Int): Int = when (cat) {
    0 -> R.string.scg_cat_hull
    1 -> R.string.scg_cat_turret
    2 -> R.string.scg_cat_gun
    3 -> R.string.scg_cat_chassis
    4 -> R.string.scg_cat_skin
    5 -> R.string.scg_cat_decor
    7 -> R.string.scg_cat_marker
    else -> R.string.scg_cat_other
}

/** LOD 预设 -> 默认勾选集合：1=仅LOD0 2=LOD0-1 0=全部；3 顶点挂点标记一律默认不选 */
private fun scgDefaultSelection(groups: List<ScgConverter.ScgGroup>, lodMode: Int): Set<Long> {
    val maxLod = when (lodMode) { 1 -> 0; 2 -> 1; else -> Int.MAX_VALUE }
    return groups.filter { it.vertexCount > 3 && it.lod <= maxLod }.map { it.id }.toSet()
}

/**
 * SCG -> OBJ 第一步：读入并解析模型，绑定配件名（用户选中的 .sc2 优先，其次同目录同名 .sc2(.dvpl)），
 * 完成 LOD 分级。失败抛出带本地化信息的异常。
 */
suspend fun parseScgForDialog(context: Context, scgUri: Uri, sc2Uri: Uri?, fileName: String,
                             extraScgs: List<Pair<String, Uri>> = emptyList()): ScgParsed =
        withContext(Dispatchers.IO) {
    val raw = context.contentResolver.openInputStream(scgUri)?.use { it.readBytes() }
        ?: throw IllegalStateException(L.s(R.string.e_cant_read))
    // DVPL 容器则先解包
    val scgData = try { ScgConverter.unwrapDvpl(raw) } catch (e: Exception) {
        throw IllegalArgumentException(L.s(R.string.e_scg_parse))
    }
    val groups = try { ScgConverter.parseScg(scgData).toMutableList() } catch (e: Exception) {
        throw IllegalArgumentException(L.s(R.string.e_scg_parse))
    }
    if (groups.isEmpty()) throw IllegalArgumentException(L.s(R.string.e_scg_parse))

    // sc2：LOD 家族 + 真名词汇表（显式选择的 sc2 优先，否则自动找同级）
    var namesFound = false
    var sc2Vocab: Set<String> = emptySet()
    val sc2Bytes = sc2Uri?.let { u ->
        context.contentResolver.openInputStream(u)?.use { it.readBytes() }
    } ?: findSiblingSc2(context, scgUri, fileName)?.let { u ->
        context.contentResolver.openInputStream(u)?.use { it.readBytes() }
    }
    if (sc2Bytes != null) {
        try {
            val sc2Data = ScgConverter.unwrapDvpl(sc2Bytes)
            val info = ScgConverter.parseSc2Info(sc2Data, groups.map { it.id }.toSet())
            for (g in groups) {
                g.family = info.familyOf(g.id)
                // 周期名与网格的配对被导出器打乱，仅挂点 HP_* 兜底用，不作部件名
                g.sc2Name = info.familyName[g.family]
            }
            sc2Vocab = info.vocab
            namesFound = info.hasFamilies
        } catch (e: Exception) { /* sc2 缺失不致命，退回几何命名 */ }
    }

    // LOD 分级（同包围盒按顶点数降序）+ 统一命名（几何分类 > 皮肤/装饰名 > 白名单名）
    // 官方参数（从已安装游戏读取的零件碰撞盒）用于真名强化，缺失时几何命名兜底
    val tankParams = try { TankParams.forTank(context, fileName) } catch (e: Exception) { null }
    ScgConverter.assignLod(groups)
    try { ScgConverter.assignNames(groups, tankParams, sc2Vocab) } catch (e: Exception) {
        // 官方参数命名异常时不阻断导出, 保留已完成的部分命名
        android.util.Log.w("TankParams", "assignNames failed: " + e.message)
    }

    // 传奇涂装 3D 外挂件：扫同级目录里本车的 Skin* 件（实测与本体同一世界坐标系），直接拼接
    // 放在 assignLod 之后：皮肤件不参与 LOD 聚类（避免同 bbox 的多件被当成 LOD 而漏选）
    var skinMerged = 0
    try {
        val key = fileName.removeSuffix(".dvpl").removeSuffix(".scg")
            .lowercase().replace(Regex("[^a-z0-9]"), "")
        if (key.length >= 3) {
            val skins = listSiblingFiles(context, scgUri) { n -> isTankSkinFile(n, key) }
            var idOff = 1_000_000L
            for ((skinName, skinUri) in skins) {
                val raw = context.contentResolver.openInputStream(skinUri)?.use { it.readBytes() } ?: continue
                val sg = try { ScgConverter.parseScg(ScgConverter.unwrapDvpl(raw)) } catch (e: Exception) { continue }
                if (sg.isEmpty()) continue
                // 皮肤件内部自带多级 LOD（实测 164/179 个外挂件含 AABB 相似簇），按簇分级，
                // 不再一律压成 lod0——否则高 LOD 网格会和 lod0 一起被全量导出/选中
                ScgConverter.assignLod(sg)
                val tag = skinName.removeSuffix(".dvpl").removeSuffix(".scg")
                var piece = 0
                for (g in sg) {
                    g.id = idOff++
                    g.name = tag + (if (sg.size > 1) "_#" + ++piece else "")
                }
                groups.addAll(sg)
                skinMerged += sg.size
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("ScgConvert", "skin merge skipped: " + e.message)
    }

    // 手动选择的附加件（多选进来的涂装 3D 件 / 其他 scg）: 同世界坐标系直接拼接
    var idOff = 2_000_000L
    for ((exName, exUri) in extraScgs) {
        val raw = try { context.contentResolver.openInputStream(exUri)?.use { it.readBytes() } } catch (e: Exception) { null } ?: continue
        val sg = try { ScgConverter.parseScg(ScgConverter.unwrapDvpl(raw)) } catch (e: Exception) { continue }
        if (sg.isEmpty()) continue
        // 附加件同样按内部 AABB 相似簇分级 LOD
        ScgConverter.assignLod(sg)
        val tag = exName.removeSuffix(".dvpl").removeSuffix(".scg")
        var piece = 0
        for (g in sg) {
            g.id = idOff++
            g.name = tag + (if (sg.size > 1) "_#" + ++piece else "")
        }
        groups.addAll(sg)
        skinMerged += sg.size
    }
    if (skinMerged > 0) android.util.Log.i("ScgConvert", "merged " + skinMerged + " extra groups")
    ScgParsed(fileName, scgUri, namesFound, groups)
}

/** SCG -> OBJ 第二步：按用户勾选导出 OBJ */
suspend fun processScg(
    context: Context,
    parsed: ScgParsed,
    selectedIds: Set<Long>,
    dirUri: Uri?,
    groupByType: Boolean
): String = withContext(Dispatchers.IO) {
    if (selectedIds.isEmpty()) throw IllegalArgumentException(L.s(R.string.e_scg_none))
    val result = ScgConverter.writeObj(parsed.groups, selectedIds)
    val outputName = parsed.fileName.removeSuffix(".dvpl").removeSuffix(".scg") + ".obj"
    val objBytes = result.objText.toByteArray(Charsets.UTF_8)
    val subDir = if (groupByType) ConvertMode.SCG_TO_OBJ.subDirName else null
    val savedPath = if (dirUri != null) {
        saveToDir(context, dirUri, outputName, objBytes, subDir)
    } else {
        saveToDownloads(context, outputName, objBytes, subDir)
    }
    android.util.Log.i("ScgConvert", "OK: " + parsed.fileName + " -> " + savedPath +
        " (" + result.keptGroups + "/" + result.totalGroups + " groups, " + result.keptVertices + " verts)")
    L.s(R.string.x_scg_done, outputName, result.keptGroups, result.totalGroups)
}

/** 在 SAF 文档的同级目录里找同名 .sc2(.dvpl)；不支持列举的提供方返回 null */
private fun findSiblingSc2(context: Context, uri: Uri, scgName: String): Uri? {
    return try {
        val docId = android.provider.DocumentsContract.getDocumentId(uri)
        val parent = docId.substringBeforeLast('/', "")
        if (parent.isEmpty()) return null
        val base = scgName.removeSuffix(".dvpl").removeSuffix(".scg")
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(uri, parent)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ), null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            var found: Uri? = null
            while (c.moveToNext() && found == null) {
                val n = c.getString(nameIdx) ?: continue
                if (n == base + ".sc2" || n == base + ".sc2.dvpl") {
                    found = android.provider.DocumentsContract.buildDocumentUri(uri.authority, c.getString(idIdx))
                }
            }
            found
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 判断 SAF 同级目录里的文件是否为本车的涂装 3D 外挂件
 * 命名规律: Skin2_R88_Object_268_hull / Skin_PzVI_tiger_I_skin2_turret_01 / mskin_T22SR_hull
 * 规则: 去掉皮肤前缀后, 必须包含本车名(去符号), 且其后紧跟部件词(hull/turret/gun/mask/style)
 */
private fun isTankSkinFile(fileName: String, tankKeyNorm: String): Boolean {
    val n = fileName.lowercase().replace(Regex("[^a-z0-9]"), "")
    if (!n.endsWith("scg")) return false               // 只收模型文件
    val mSkin = Regex("^mskin").find(n) ?: Regex("^skin[0-9]*").find(n) ?: return false
    val rest = n.substring(mSkin.value.length)
    val ki = rest.indexOf(tankKeyNorm)
    if (ki < 0) return false
    var tail = rest.substring(ki + tankKeyNorm.length)
    tail = Regex("^skin[0-9]*").replace(tail, "")      // 内嵌 skin2/skin3 代数标记
    if (tail.startsWith("style")) tail = tail.removePrefix("style")
    return tail.startsWith("hull") || tail.startsWith("turret") ||
        tail.startsWith("gun") || tail.startsWith("mask")
}

/** 列出 SAF 同级目录里满足条件的文件（供涂装 3D 件扫描） */
private fun listSiblingFiles(context: Context, uri: Uri, filter: (String) -> Boolean): List<Pair<String, Uri>> {
    val out = ArrayList<Pair<String, Uri>>()
    try {
        val docId = android.provider.DocumentsContract.getDocumentId(uri)
        val parent = docId.substringBeforeLast('/', "")
        if (parent.isEmpty()) return out
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(uri, parent)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ), null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) {
                val nm = c.getString(nameIdx) ?: continue
                if (filter(nm)) out.add(nm to android.provider.DocumentsContract.buildDocumentUri(uri.authority, c.getString(idIdx)))
            }
        }
    } catch (e: Exception) { /* 提供方不支持列举则跳过 */ }
    return out
}

/**
 * Wwise 解包/打包批量处理（与纹理管线独立：解包多文件并行，打包按库逐个重建）
 */
suspend fun processWwiseBatch(
    context: Context,
    uris: List<Uri>,
    mode: ConvertMode,
    dirUri: Uri?,
    groupByType: Boolean
): List<Triple<String, Boolean, String>> = withContext(Dispatchers.IO) {
    data class Sel(val uri: Uri, val name: String)
    val sels = uris.map { Sel(it, queryFileName(context, it)) }

    // SoundbanksInfo.json 识别（按文件名；自动解 DVPL 包裹），提供 wemId → 原始文件名/目录/事件
    var info = WwiseConverter.SoundbanksInfo(emptyMap(), emptyMap(), emptyMap())
    val jsonSels = sels.filter { it.name.contains("soundbanksinfo", ignoreCase = true) }
    val otherSels = sels.filterNot { it.name.contains("soundbanksinfo", ignoreCase = true) }
    for (js in jsonSels) {
        try {
            val raw = context.contentResolver.openInputStream(js.uri)?.use { it.readBytes() } ?: continue
            val unwrapped = if (DvplCodec.isDvplFile(raw)) DvplCodec.decode(raw) else raw
            val parsed = WwiseConverter.parseSoundbanksInfo(unwrapped)
            info = WwiseConverter.SoundbanksInfo(
                info.names + parsed.names, info.dirs + parsed.dirs,
                // events 合并：id 同时出现在多个 json 时拼接去重
                (info.events.keys + parsed.events.keys).associateWith { id ->
                    ((info.events[id] ?: emptyList()) + (parsed.events[id] ?: emptyList())).distinct()
                },
                info.eventNames + parsed.eventNames
            )
        } catch (e: Exception) {
            android.util.Log.w("Wwise", L.s(R.string.e_sbi_parse) + js.name, e)
        }
    }
    val nameMap = info.names

    // 模式级预检查失败不抛异常（会穿透协程导致崩溃），统一转为失败记录返回
    try {
    if (mode == ConvertMode.WWISE_UNPACK) {
        if (otherSels.isEmpty()) throw IllegalStateException(L.s(R.string.e_no_pck))
        // 预扫描已选 .pck：完整版 id 集 + 库名集合（同名 pck+bnk 成对时 pck 副本优先，
        // bnk 里的截断预取副本跳过，避免出现「同名 (1)」重复文件）
        val bankNameRe = Regex("\\.(pck|bnk)(\\.dvpl)?$", RegexOption.IGNORE_CASE)
        fun stemOf(name: String): String {
            var s = name
            if (s.endsWith(".dvpl", true)) s = s.dropLast(5)
            return s.dropLast(4)
        }
        val pckSels = otherSels.filter { bankNameRe.containsMatchIn(it.name) && it.name.contains(".pck", true) }
        val pckFullIds = HashSet<Long>()
        for (sel in pckSels) {
            try {
                val raw = context.contentResolver.openInputStream(sel.uri)?.use { it.readBytes() } ?: continue
                val data = if (DvplCodec.isDvplFile(raw)) DvplCodec.decode(raw) else raw
                val bank = WwiseConverter.parse(data) ?: continue
                for (e in bank.entries) pckFullIds.add(e.id)
            } catch (_: Exception) { /* 解析失败在并行阶段按文件报错 */ }
        }
        val dispatcher = Dispatchers.IO.limitedParallelism(2)
        kotlinx.coroutines.coroutineScope {
            otherSels.map { sel ->
                async(dispatcher) {
                    var fName = sel.name
                    try {
                        currentCoroutineContext().ensureActive()
                        val input = context.contentResolver.openInputStream(sel.uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException(L.s(R.string.e_cant_read))
                        val wasDvpl = DvplCodec.isDvplFile(input)
                        val data = if (wasDvpl) DvplCodec.decode(input) else input
                        val bank = WwiseConverter.parse(data)
                        if (bank == null) {
                            // 内容嗅探：可能是改名的 json，静默跳过
                            if (data.isNotEmpty() && data[0] == '{'.code.toByte()) {
                                return@async Triple(sel.name, true, L.s(R.string.x_skip_json))
                            }
                            throw IllegalStateException(L.s(R.string.e_not_pck))
                        }
                        if (bank.entries.isEmpty()) throw IllegalStateException(L.s(R.string.x_event_only))
                        val stem = stemOf(sel.name)
                        var extracted = 0
                        var deduped = 0
                        var truncWarn = 0
                        for (e in bank.entries) {
                            val wem = bank.extract(e)
                            if (!bank.isPck && WwiseConverter.isTruncatedWem(wem)) {
                                if (pckFullIds.contains(e.id)) { deduped++; continue }
                                truncWarn++
                            }
                            val outName = WwiseConverter.exportName(e.id, nameMap)
                            // 分类：Wwise解包/{库名}/（{原始目录}/...）（无名称时入库名文件夹）
                            val dir = info.dirs[e.id]
                            val subDir = when {
                                !groupByType -> null
                                dir.isNullOrEmpty() -> mode.subDirName + "/" + stem
                                else -> mode.subDirName + "/" + stem + "/" + dir
                            }
                            if (dirUri != null) saveToDir(context, dirUri, outName, wem, subDir)
                            else saveToDownloads(context, outName, wem, subDir)
                            extracted++
                        }
                        val named = bank.entries.count { nameMap.containsKey(it.id) }
                        val notes = ArrayList<String>()
                        if (named > 0) notes.add(L.s(R.string.tpl_named_count, named))
                        else notes.add(L.s(R.string.x_no_sbi))
                        if (deduped > 0) notes.add(L.s(R.string.tpl_dedup_skip, deduped))
                        if (truncWarn > 0) notes.add(L.s(R.string.tpl_trunc_warn, truncWarn))
                        Triple(sel.name, true, L.s(R.string.x_extract_prefix) + extracted + L.s(R.string.x_wem_suffix) + notes.joinToString("；") + "）")
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.e("WwiseUnpack", "FAIL: " + fName, e)
                        Triple(fName, false, e.message ?: e.javaClass.simpleName)
                    }
                }
            }.awaitAll()
        }
    } else if (mode == ConvertMode.WWISE_TO_OGG) {
        // ===== WWISE_TO_OGG：wem → ogg（Vorbis）或 wav（PCM）；也支持 .pck/.bnk 整库直转 =====
        if (otherSels.isEmpty()) throw IllegalStateException(L.s(R.string.e_no_wem_pck))
        val bankNameRe = Regex("\\.(pck|bnk)(\\.dvpl)?$", RegexOption.IGNORE_CASE)
        val bad = otherSels.filterNot { it.name.endsWith(".wem", true) || bankNameRe.containsMatchIn(it.name) }
        if (bad.isNotEmpty()) throw IllegalStateException(L.s(R.string.e_unsupported_file) + bad.first().name +
            L.s(R.string.x_mode_supports))
        fun stemOf(name: String): String {
            var s = name
            if (s.endsWith(".dvpl", true)) s = s.dropLast(5)
            return s.dropLast(4)
        }
        val dispatcher = Dispatchers.IO.limitedParallelism(4)
        kotlinx.coroutines.coroutineScope {
            otherSels.map { sel ->
                async(dispatcher) {
                    var fName = sel.name
                    try {
                        currentCoroutineContext().ensureActive()
                        val isBank = !sel.name.endsWith(".wem", true)
                        if (!isBank) {
                            // ---- 单个 .wem ----
                            val wemBytes = context.contentResolver.openInputStream(sel.uri)?.use { it.readBytes() }
                                ?: throw IllegalStateException(L.s(R.string.e_cant_read))
                            if (WwiseConverter.isTruncatedWem(wemBytes))
                                throw IllegalStateException(
                                    L.s(R.string.e_trunc_prefetch))
                            val id = WwiseConverter.parseWemFileName(sel.name).first
                            val origName = id?.let { nameMap[it] }
                            val baseName = origName ?: sel.name.removeSuffix(".wem").removeSuffix(".WEM")
                            val dir = id?.let { info.dirs[it] }
                            val subDir = when {
                                !groupByType -> null
                                dir.isNullOrEmpty() -> mode.subDirName
                                else -> mode.subDirName + "/" + dir
                            }
                            if (WwiseConverter.isPcmWem(wemBytes)) {
                                val outName = makeUniqueSafeName(baseName, "wav")
                                if (dirUri != null) saveToDir(context, dirUri, outName, wemBytes, subDir)
                                else saveToDownloads(context, outName, wemBytes, subDir)
                                listOf(Triple(sel.name, true, L.s(R.string.x_pcm_wav)))
                            } else if (WwiseConverter.isWwiseNewPcmWem(wemBytes)) {
                                // Wwise 2021+ 新版 PCM（codec 0xFFFE）：重建标准 WAV
                                val wavBytes = WwiseConverter.wemNewPcmToWav(wemBytes)
                                val outName = makeUniqueSafeName(baseName, "wav")
                                if (dirUri != null) saveToDir(context, dirUri, outName, wavBytes, subDir)
                                else saveToDownloads(context, outName, wavBytes, subDir)
                                listOf(Triple(sel.name, true, L.s(R.string.x_pcm_wav)))
                            } else if (WwiseConverter.isPtAdpcmWem(wemBytes)) {
                                val wavBytes = WwiseConverter.decodePtAdpcmToWav(wemBytes)
                                val outName = makeUniqueSafeName(baseName, "wav")
                                if (dirUri != null) saveToDir(context, dirUri, outName, wavBytes, subDir)
                                else saveToDownloads(context, outName, wavBytes, subDir)
                                listOf(Triple(sel.name, true, L.s(R.string.x_ptadpcm_wav)))
                            } else if (WwiseConverter.isImaAdpcmWem(wemBytes)) {
                                val wavBytes = WwiseConverter.decodeImaAdpcmToWav(wemBytes)
                                val outName = makeUniqueSafeName(baseName, "wav")
                                if (dirUri != null) saveToDir(context, dirUri, outName, wavBytes, subDir)
                                else saveToDownloads(context, outName, wavBytes, subDir)
                                listOf(Triple(sel.name, true, L.s(R.string.x_ima_wav)))
                            } else if (WwiseConverter.isOpusWem(wemBytes)) {
                                val wavBytes = WwiseConverter.decodeOpusToWav(wemBytes, context)
                                val outName = makeUniqueSafeName(baseName, "wav")
                                if (dirUri != null) saveToDir(context, dirUri, outName, wavBytes, subDir)
                                else saveToDownloads(context, outName, wavBytes, subDir)
                                listOf(Triple(sel.name, true, L.s(R.string.x_opus_wav)))
                            } else {
                                val oggBytes = WwiseNative.convertWemToOgg(context, wemBytes)
                                val outName = makeUniqueSafeName(baseName, "ogg")
                                if (dirUri != null) saveToDir(context, dirUri, outName, oggBytes, subDir)
                                else saveToDownloads(context, outName, oggBytes, subDir)
                                listOf(Triple(sel.name, true, L.s(R.string.x_converted_ogg)))
                            }
                        } else {
                            // ---- .pck/.bnk 整库直转 ----
                            val input = context.contentResolver.openInputStream(sel.uri)?.use { it.readBytes() }
                                ?: throw IllegalStateException(L.s(R.string.e_cant_read))
                            val data = if (DvplCodec.isDvplFile(input)) DvplCodec.decode(input) else input
                            val bank = WwiseConverter.parse(data)
                                ?: throw IllegalStateException(L.s(R.string.e_not_pck))
                            if (bank.entries.isEmpty())
                                return@async listOf(Triple(sel.name, true, L.s(R.string.x_skip_event_only)))
                            val stem = stemOf(sel.name)
                            var ok = 0
                            var oggN = 0
                            var wavN = 0
                            var skipped = 0
                            var failed = 0
                            var firstErr: String? = null
                            for (e in bank.entries) {
                                try {
                                    currentCoroutineContext().ensureActive()
                                    val wem = bank.extract(e)
                                    if (WwiseConverter.isTruncatedWem(wem)) { skipped++; continue }
                                    val origName = nameMap[e.id]
                                    // 带 id 前缀命名（同解包），转回 wem 后可直接按 ID 回打
                                    val baseName = if (origName != null) e.id.toString() + "_" + origName else e.id.toString()
                                    val dir = info.dirs[e.id]
                                    val subDir = when {
                                        !groupByType -> null
                                        dir.isNullOrEmpty() -> mode.subDirName + "/" + stem
                                        else -> mode.subDirName + "/" + stem + "/" + dir
                                    }
                                    if (WwiseConverter.isPcmWem(wem)) {
                                        val outName = makeUniqueSafeName(baseName, "wav")
                                        if (dirUri != null) saveToDir(context, dirUri, outName, wem, subDir)
                                        else saveToDownloads(context, outName, wem, subDir)
                                        wavN++
                                    } else if (WwiseConverter.isWwiseNewPcmWem(wem)) {
                                        // Wwise 2021+ 新版 PCM（codec 0xFFFE）：重建标准 WAV
                                        val wavBytes = WwiseConverter.wemNewPcmToWav(wem)
                                        val outName = makeUniqueSafeName(baseName, "wav")
                                        if (dirUri != null) saveToDir(context, dirUri, outName, wavBytes, subDir)
                                        else saveToDownloads(context, outName, wavBytes, subDir)
                                        wavN++
                                    } else if (WwiseConverter.isPtAdpcmWem(wem)) {
                                        val wavBytes = WwiseConverter.decodePtAdpcmToWav(wem)
                                        val outName = makeUniqueSafeName(baseName, "wav")
                                        if (dirUri != null) saveToDir(context, dirUri, outName, wavBytes, subDir)
                                        else saveToDownloads(context, outName, wavBytes, subDir)
                                        wavN++
                                    } else if (WwiseConverter.isImaAdpcmWem(wem)) {
                                        val wavBytes = WwiseConverter.decodeImaAdpcmToWav(wem)
                                        val outName = makeUniqueSafeName(baseName, "wav")
                                        if (dirUri != null) saveToDir(context, dirUri, outName, wavBytes, subDir)
                                        else saveToDownloads(context, outName, wavBytes, subDir)
                                        wavN++
                                    } else if (WwiseConverter.isOpusWem(wem)) {
                                        val wavBytes = WwiseConverter.decodeOpusToWav(wem, context)
                                        val outName = makeUniqueSafeName(baseName, "wav")
                                        if (dirUri != null) saveToDir(context, dirUri, outName, wavBytes, subDir)
                                        else saveToDownloads(context, outName, wavBytes, subDir)
                                        wavN++
                                    } else {
                                        val oggBytes = WwiseNative.convertWemToOgg(context, wem)
                                        val outName = makeUniqueSafeName(baseName, "ogg")
                                        if (dirUri != null) saveToDir(context, dirUri, outName, oggBytes, subDir)
                                        else saveToDownloads(context, outName, oggBytes, subDir)
                                        oggN++
                                    }
                                    ok++
                                } catch (ex: Exception) {
                                    if (ex is kotlinx.coroutines.CancellationException) throw ex
                                    failed++
                                    if (firstErr == null) firstErr = ex.message ?: ex.javaClass.simpleName
                                    android.util.Log.e("WwiseOgg", "FAIL: " + fName + " #" + e.id, ex)
                                }
                            }
                            val notes = ArrayList<String>()
                            notes.add(L.s(R.string.x_ogg_out_prefix) + oggN + L.s(R.string.x_ogg_out_mid) + wavN + L.s(R.string.x_ogg_out_suffix) + mode.subDirName + "/" + stem + "/）")
                            if (skipped > 0) notes.add(L.s(R.string.x_skip_prefix) + skipped + L.s(R.string.x_trunc_skip_suffix))
                            if (failed > 0) notes.add(L.s(R.string.x_fail_prefix) + failed + L.s(R.string.x_fail_suffix) + firstErr)
                            listOf(Triple(sel.name, ok > 0, notes.joinToString("；")))
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.e("WwiseOgg", "FAIL: " + fName, e)
                        listOf(Triple(fName, false, e.message ?: e.javaClass.simpleName))
                    }
                }
            }.awaitAll().flatten()
        }
    } else if (mode == ConvertMode.WWISE_TO_WEM) {
        // ===== WWISE_TO_WEM：音频 → wem（支持 WAV/OGG/MP3/M4A/FLAC 等；内置 libvorbis 编码） =====
        if (otherSels.isEmpty()) throw IllegalStateException(L.s(R.string.e_no_audio))
        val supportedExts = setOf("wav", "ogg", "mp3", "m4a", "aac", "flac", "opus")
        val bad = otherSels.filterNot { sel ->
            val ext = sel.name.substringAfterLast('.', "").lowercase()
            ext in supportedExts
        }
        if (bad.isNotEmpty()) throw IllegalStateException(L.s(R.string.e_unsupported_audio_type) + bad.first().name +
            L.s(R.string.x_audio_formats))
        // 码书库只读一次，所有协程共用同一份字节
        val packedCodebooks = WwiseNative.codebookFile(context).readBytes()
        val dispatcher = Dispatchers.IO.limitedParallelism(4)
        kotlinx.coroutines.coroutineScope {
            otherSels.map { sel ->
                async(dispatcher) {
                    var fName = sel.name
                    try {
                        currentCoroutineContext().ensureActive()

                        // 统一解码为 PCM 再用内置 aoTuV b6.03 编码为 OGG：
                        // 外部 OGG（ffmpeg/标准 libvorbis）的码书与 Wwise 库不一致，必须重编码
                        val pcmResult = decodeToPcm(context, sel.uri)
                        val oggBytes: ByteArray = WwiseNative.pcmToOgg(
                            pcmResult.pcm, pcmResult.sampleRate, pcmResult.channels
                        ) ?: throw IllegalStateException(L.s(R.string.e_vorbis_enc))

                        val wemBytes = WwiseConverter.oggToWem(oggBytes, packedCodebooks)
                        val problem = WwiseConverter.validateWemStructure(wemBytes)
                        if (problem != null) throw IllegalStateException(L.s(R.string.e_selfcheck) + problem)
                        val baseName = sel.name.substringBeforeLast('.')
                        val outName = makeUniqueSafeName(baseName, "wem")
                        val subDir = if (groupByType) mode.subDirName else null
                        if (dirUri != null) saveToDir(context, dirUri, outName, wemBytes, subDir)
                        else saveToDownloads(context, outName, wemBytes, subDir)
                        val samples = WwiseConverter.readWemSampleCount(wemBytes)
                        val rate = WwiseConverter.readWemSampleRate(wemBytes)
                        val dur = if (samples > 0 && rate > 0) String.format(L.s(R.string.fmt_seconds), samples.toDouble() / rate) else ""
                        Triple(sel.name, true, L.s(R.string.x_wem_done) + wemBytes.size + "B" +
                            (if (dur.isNotEmpty()) "，" + dur else "") + "）")
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.e("AudioToWem", "FAIL: " + fName, e)
                        Triple(fName, false, e.message ?: e.javaClass.simpleName)
                    }
                }
            }.awaitAll()
        }
    } else {
        // ===== WWISE_PACK：1 个目标库（或同名 .pck + .bnk 成对）+ 若干替换 wem（可选 json 帮跨语言名称匹配） =====
        val bankRe = Regex("\\.(pck|bnk)(\\.dvpl)?$", RegexOption.IGNORE_CASE)
        val banks = otherSels.filter { bankRe.containsMatchIn(it.name) }
        val wems = otherSels.filter { it.name.endsWith(".wem", ignoreCase = true) }
        val unknown = otherSels.filterNot { bankRe.containsMatchIn(it.name) || it.name.endsWith(".wem", ignoreCase = true) }
        if (unknown.isNotEmpty()) throw IllegalStateException(L.s(R.string.e_unknown_file) + unknown.first().name)
        if (wems.isEmpty()) throw IllegalStateException(L.s(R.string.e_no_replacement))

        // 去掉 .pck/.bnk 与 .dvpl 后缀取同名主干，用于成对校验
        fun stemOf2(n: String): String {
            var s = n
            if (s.endsWith(".dvpl", true)) s = s.dropLast(5)
            if (s.endsWith(".pck", true) || s.endsWith(".bnk", true)) s = s.dropLast(4)
            return s
        }

        if (banks.size == 2) {
            val p = banks.firstOrNull { stemOf2(it.name) != it.name && it.name.removeSuffix(".dvpl").endsWith(".pck", true) }
            val b = banks.firstOrNull { stemOf2(it.name) != it.name && it.name.removeSuffix(".dvpl").endsWith(".bnk", true) }
            if (p == null || b == null || stemOf2(p.name) != stemOf2(b.name))
                throw IllegalStateException(L.s(R.string.e_two_banks))
        } else if (banks.size != 1) {
            throw IllegalStateException(L.s(R.string.e_need_bank) + banks.size + L.s(R.string.x_detail_suffix))
        }
        val pairMode = banks.size == 2

        // 预读全部 wem（成对模式两个库都要用）
        val wemFiles = wems.map { w ->
            val bytes = context.contentResolver.openInputStream(w.uri)?.use { it.readBytes() }
                ?: throw IllegalStateException(L.s(R.string.e_cant_read_name) + w.name)
            w.name to bytes
        }

        banks.map { bankSel ->
            val fName = bankSel.name
            try {
                currentCoroutineContext().ensureActive()
                val input = context.contentResolver.openInputStream(bankSel.uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException(L.s(R.string.e_cant_read))
                val wasDvpl = DvplCodec.isDvplFile(input)
                val data = if (wasDvpl) DvplCodec.decode(input) else input
                val bank = WwiseConverter.parse(data)
                    ?: throw IllegalStateException(L.s(R.string.e_not_pck))

                // 目标库的 名称 → id 反查表（需 json 提供名称；同库替换走 id 直配，不依赖 json）
                val idSet = bank.entries.map { it.id }.toHashSet()
                val nameToId = HashMap<String, Long>()
                for (e in bank.entries) nameMap[e.id]?.let { nameToId.putIfAbsent(it, e.id) }

                val replacements = HashMap<Long, ByteArray>()
                val unmatched = ArrayList<String>()
                for ((wName, wemBytes) in wemFiles) {
                    val (id, name) = WwiseConverter.parseWemFileName(wName)
                    val bareName = wName.removeSuffix(".wem").removeSuffix(".WEM")
                    val targetId = when {
                        id != null && idSet.contains(id) -> id
                        name != null && nameToId.containsKey(name) -> nameToId[name]
                        // 无 id 前缀的自定义文件名（如 OGG 转 WEM 的输出）：按 json 原名匹配
                        nameToId.containsKey(bareName) -> nameToId[bareName]
                        else -> null
                    }
                    if (targetId == null) unmatched.add(wName)
                    else replacements[targetId] = wemBytes
                }
                if (!pairMode && unmatched.isNotEmpty()) {
                    throw IllegalStateException(L.s(R.string.x_unmatched) +
                        unmatched.take(3).joinToString() + (if (unmatched.size > 3) L.s(R.string.x_and_more) + unmatched.size + L.s(R.string.x_unit_suffix) else ""))
                }
                if (replacements.isEmpty()) {
                    return@map listOf(Triple(fName, false,
                        if (pairMode) L.s(R.string.x_no_match_pckbnk)
                        else L.s(R.string.x_no_match)))
                }

                val repacked = WwiseConverter.repack(bank, replacements)
                val outData = if (wasDvpl) DvplCodec.encode(repacked.bytes, DvplCodec.COMPRESSION_LZ4_HC) else repacked.bytes

                var baseName = bankSel.name
                if (baseName.endsWith(".dvpl", ignoreCase = true)) baseName = baseName.dropLast(5)
                val isPckName = baseName.endsWith(".pck", ignoreCase = true)
                val stem = baseName.dropLast(4)
                val outName = stem + "_repacked" + (if (isPckName) ".pck" else ".bnk") + (if (wasDvpl) ".dvpl" else "")
                val subDir = if (groupByType) mode.subDirName else null
                val savedPath = if (dirUri != null) saveToDir(context, dirUri, outName, outData, subDir)
                else saveToDownloads(context, outName, outData, subDir)
                val notes = ArrayList<String>()
                notes.add(L.s(R.string.x_replaced_prefix) + replacements.size + L.s(R.string.x_replaced_suffix) + savedPath)
                if (pairMode && unmatched.isNotEmpty())
                    notes.add(L.s(R.string.x_skip_prefix) + unmatched.size + L.s(R.string.x_skipped_foreign))
                if (repacked.truncatedReplacements > 0)
                    notes.add(L.s(R.string.x_of_which) + repacked.truncatedReplacements + L.s(R.string.x_truncated_prefix) +
                        (if (!isPckName && !pairMode) L.s(R.string.x_truncated_hint) else ""))
                listOf(Triple(fName, true, notes.joinToString("；")))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("WwisePack", "FAIL: " + fName, e)
                listOf(Triple(fName, false, e.message ?: e.javaClass.simpleName))
            }
        }.flatten()
    }
    } catch (e: Exception) {
        // 模式级预检查失败（选错文件类型/数量等）：转为失败记录，不再穿透协程导致应用崩溃
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.e("Wwise", "MODE FAIL: " + e.message, e)
        val modeLabel = L.s(mode.titleRes)
        listOf(Triple("Wwise " + modeLabel,
            false, e.message ?: e.javaClass.simpleName))
    }
}

/** MediaExtractor 解码结果：16-bit LE interleaved PCM */
internal data class PcmResult(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

/**
 * 用 Android MediaExtractor + MediaCodec 把任意音频 Uri 解码为 16-bit LE PCM。
 * 支持 WAV/MP3/M4A/AAC/FLAC/OPUS 等所有系统解码器支持的格式。
 */
internal fun decodeToPcm(context: android.content.Context, uri: android.net.Uri): PcmResult {
    val extractor = android.media.MediaExtractor()
    extractor.setDataSource(context, uri, null)

    // 找音频轨
    var trackIndex = -1
    var format: android.media.MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
        val fmt = extractor.getTrackFormat(i)
        val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
            trackIndex = i
            format = fmt
            break
        }
    }
    if (trackIndex < 0 || format == null) {
        extractor.release()
        throw IllegalArgumentException(L.s(R.string.e_no_audio_track))
    }

    val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
    val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
    val channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)

    extractor.selectTrack(trackIndex)

    val codec = android.media.MediaCodec.createDecoderByType(mime)
    codec.configure(format, null, null, 0)
    codec.start()

    val out = java.io.ByteArrayOutputStream()
    val timeoutUs = 10_000L
    var inputDone = false
    var outputDone = false
    var outputFormat: android.media.MediaFormat? = null
    // 卡死防护：损坏/不支持的文件可能永不产生 END_OF_STREAM。
    // 每轮记录是否有进展（入队或出队成功），连续 1000 轮（约 10 秒）
    // 无进展即判定解码器停摆，抛错释放线程
    var idleSpins = 0

    try {
        while (!outputDone) {
            var progressed = false
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(timeoutUs)
                if (inIdx >= 0) {
                    progressed = true
                    val buf = codec.getInputBuffer(inIdx)!!
                    val sz = extractor.readSampleData(buf, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val info = android.media.MediaCodec.BufferInfo()
            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                progressed = true
                outputFormat = codec.outputFormat
            } else if (outIdx >= 0) {
                progressed = true
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                    outputDone = true
                val buf = codec.getOutputBuffer(outIdx)!!
                val bytes = ByteArray(info.size)
                buf.get(bytes)
                out.write(bytes)
                codec.releaseOutputBuffer(outIdx, false)
            }
            if (!progressed && ++idleSpins > 1000)
                throw IllegalStateException(L.s(R.string.e_decode_timeout))
            if (progressed) idleSpins = 0
        }
    } finally {
        codec.stop()
        codec.release()
        extractor.release()
    }

    var rawPcm = out.toByteArray()
    if (rawPcm.isEmpty()) throw IllegalStateException(L.s(R.string.e_empty_decode))

    // 检查实际输出编码格式：Android 部分设备/格式（FLAC、高采样率）会输出 float32
    val pcmEncoding = outputFormat?.let {
        if (it.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING))
            it.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING)
        else android.media.AudioFormat.ENCODING_PCM_16BIT
    } ?: android.media.AudioFormat.ENCODING_PCM_16BIT

    val pcm16: ByteArray = if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
        // float32 LE → int16 LE
        val floatBuf = java.nio.ByteBuffer.wrap(rawPcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out16 = java.nio.ByteBuffer.allocate(rawPcm.size / 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val shortBuf = out16.asShortBuffer()
        while (floatBuf.hasRemaining()) {
            val f = floatBuf.get().coerceIn(-1f, 1f)
            shortBuf.put((f * 32767f).toInt().toShort())
        }
        out16.array()
    } else {
        rawPcm
    }

    return PcmResult(pcm16, sampleRate, channels)
}

/** wem 转换输出名：{安全基名}.{ext}（基名去掉原扩展，替换非法字符） */
private fun makeUniqueSafeName(baseName: String, ext: String): String {
    val safe = baseName.replace(Regex("[\\/:*?\"<>|]"), "_").take(80).ifEmpty { "audio" }
    return "$safe.$ext"
}

/**
 * 生成带平台格式标签的输出文件名（实测游戏命名约定：PC=xxx.dx11.dds，移动端=xxx.astc.pvr）
 * 剥离输入的 .dvpl 包裹、旧格式标签（.astc/.dx11，大小写不敏感）、旧扩展名后拼上目标标签
 * 例：xxx.dx11.dds.dvpl + ".astc.pvr" → xxx.astc.pvr
 */
/** 文件是否为视频（mime video 前缀优先；SAF 常给 octet-stream，扩展名兜底） */
private fun isVideoFile(context: Context, uri: Uri, fileName: String): Boolean {
    val mime = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
    if (mime != null) {
        if (mime.startsWith("video/")) return true
        if (mime.startsWith("image/")) return false
    }
    val n = fileName.lowercase()
    return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") ||
        n.endsWith(".3gp") || n.endsWith(".3g2") || n.endsWith(".webm") ||
        n.endsWith(".avi") || n.endsWith(".m4v") || n.endsWith(".ts") || n.endsWith(".mts")
}

private fun retagFileName(fileName: String, tagExt: String): String {
    var name = fileName
    if (name.endsWith(".dvpl", ignoreCase = true)) name = name.dropLast(5)  // 剥 dvpl 包裹
    val dot = name.lastIndexOf('.')
    if (dot > 0) name = name.substring(0, dot)                             // 剥旧扩展名
    val lower = name.lowercase()
    if (lower.endsWith(".astc") || lower.endsWith(".dx11")) name = name.dropLast(5) // 剥旧格式标签
    return name + tagExt
}

/** PVR 输出标签：4444 是 PC DX11 格式用 .dx11.pvr，其余（ASTC/未压缩）用 .astc.pvr（与游戏数据一致） */
private fun pvrTag(quality: PvrConverter.AstcQuality): String =
    if (quality == PvrConverter.AstcQuality.RGBA_4444_PC) ".dx11.pvr" else ".astc.pvr"

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
internal fun queryFileName(context: Context, uri: Uri): String {
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
    // 上限耗尽仍冲突：加时间戳兜底，绝不静默返回已存在的名称
    if (existing.contains(candidate))
        candidate = base + " (" + System.currentTimeMillis() + ")" + ext
    return candidate
}

/**
 * 保存到自定义 SAF 目录
 */
@Synchronized
/** 输出 MIME: 正确的 mime 才能被相册/看图软件关联解码（octet-stream 会被当未知文件打不开） */
private fun mimeForFileName(name: String): String = when {
    name.endsWith(".webp", ignoreCase = true) -> "image/webp"
    name.endsWith(".png", ignoreCase = true) -> "image/png"
    name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
    name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
    else -> "application/octet-stream"
}

internal fun saveToDir(
    context: Context,
    dirUri: Uri,
    fileName: String,
    data: ByteArray,
    subDir: String? = null
): String {
    var dir = DocumentFile.fromTreeUri(context, dirUri)
        ?: throw IllegalStateException(L.s(R.string.e_no_export_access))
    if (!dir.isDirectory || !dir.canWrite()) {
        throw IllegalStateException(L.s(R.string.e_export_readonly))
    }
    if (subDir != null) {
        // 支持嵌套子目录（Wwise 按原始路径分类：Tracks/loops 等），逐级创建
        for (seg in subDir.split('/').filter { it.isNotBlank() }) {
            dir = dir.findFile(seg)?.takeIf { it.isDirectory } ?: dir.createDirectory(seg)
                ?: throw IllegalStateException(L.s(R.string.e_mkdir) + seg)
        }
    }
    val uniqueName = makeUniqueFileName(dir, fileName)
    val file = dir.createFile(mimeForFileName(uniqueName), uniqueName)
        ?: throw IllegalStateException(L.s(R.string.e_create_file))
    context.contentResolver.openOutputStream(file.uri)?.use { it.write(data) }
        ?: throw IllegalStateException(L.s(R.string.e_write_file))
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
        // 上限耗尽仍冲突：加时间戳兜底，绝不静默返回已存在的名称
        if (existing.contains(candidate))
            candidate = base + " (" + System.currentTimeMillis() + ")" + ext
        return candidate
    } catch (e: Exception) {
        return fileName
    }
}

/**
 * 保存到公共下载目录（MediaStore，Android 10+ 无需权限）
 */
internal fun saveToDownloads(
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
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeForFileName(uniqueName))
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS + "/DVPLModHelper" + (subDir?.let { "/" + it } ?: ""))
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: throw IllegalStateException(L.s(R.string.e_output_file))
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                ?: throw IllegalStateException(L.s(R.string.e_write_file))
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
                text = L.s(R.string.app_welcome),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = L.s(R.string.app_subtitle),
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