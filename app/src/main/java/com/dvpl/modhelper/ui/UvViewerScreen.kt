package com.dvpl.modhelper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dvpl.modhelper.L
import com.dvpl.modhelper.Prefs
import com.dvpl.modhelper.R
import com.dvpl.modhelper.ScgGlView
import com.dvpl.modhelper.saveToDir
import com.dvpl.modhelper.saveToDownloads
import com.dvpl.modhelper.codec.DdsConverter
import com.dvpl.modhelper.codec.DvplCodec
import com.dvpl.modhelper.codec.ObjReader
import com.dvpl.modhelper.codec.PvrConverter
import com.dvpl.modhelper.codec.ScgConverter
import com.dvpl.modhelper.codec.UvPart
import com.dvpl.modhelper.parseScgForDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 画布底色: 与 UvGlView GL 清屏色(0.13/0.14/0.16)一致——GL 首帧前的空档融入画布,
 *  不再透出浅色主题的窗口底(白屏一闪的来源) */
private val UvCanvasBg = Color(0xFF212429)

/**
 * UV 查看器（Blender 风格）: SCG(.dvpl)/OBJ 输入;
 * 2D UV 图（线框/贴图/叠加, 像素级缩放 0.2-128x, 选中高亮/其余淡化）+
 * 3D 贴图预览（SCG 与 OBJ 输入均可, 贴图实时上模型, 部件点击独显）。
 * SCG 支持多选: 本体 + sc2 + 涂装/附加件（同世界坐标系拼接）。
 * 贴图可给单个部件或全局, 两视图同步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UvViewerScreen(files: List<Pair<Uri, String>>, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = remember { mutableStateOf<UvGlView?>(null) }
    val view3d = remember { mutableStateOf<ScgGlView?>(null) }

    var parts by remember { mutableStateOf<List<UvPart>>(emptyList()) }
    var scgGroups by remember { mutableStateOf<List<ScgConverter.ScgGroup>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf(UvGlView.MODE_WIREFRAME) }
    var nearest by rememberSaveable { mutableStateOf(false) }
    var lod0Only by rememberSaveable { mutableStateOf(true) }
    var show3d by rememberSaveable { mutableStateOf(false) }
    // 首次进入 3D 后 3D 视图常驻（只切可见性不销毁）: 每次销毁重建都要重走
    // EGL 初始化+着色器编译+顶点上传, 新视图首帧前渲染框透出窗口底色——白屏一闪
    var ever3d by rememberSaveable { mutableStateOf(false) }
    var soloIdx by remember { mutableStateOf<Int?>(null) }
    var hidden by remember { mutableStateOf(emptySet<Int>()) }
    var texError by remember { mutableStateOf<String?>(null) }
    // 贴图登记表（部件下标 null=全局）; 2D/3D 视图重建后按表重挂
    val texMap = remember { mutableStateMapOf<Int?, Bitmap>() }
    var texTarget by remember { mutableStateOf<Int?>(null) }   // null = 应用到全部

    // ===== 解析（IO 线程） =====
    LaunchedEffect(files) {
        withContext(Dispatchers.IO) {
            try {
                val scgPick = files.filter { it.second.contains(".scg", true) }
                val sc2Pick = files.filter { it.second.contains(".sc2", true) }
                val objPick = files.filter { it.second.contains(".obj", true) }
                if (objPick.isNotEmpty()) {
                    // OBJ: 位置汤 + UV 汤（3D 预览用 pos）
                    scgGroups = null
                    val (uri, name) = objPick.first()
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException(L.s(R.string.e_cant_read))
                    val res = ObjReader.parse(raw)
                    if (res.isEmpty()) throw IllegalArgumentException(L.s(R.string.e_obj_parse))
                    parts = res
                } else {
                    // SCG(.dvpl) 多选: 第一个 = 本体, 其余 = 涂装/附加件（同世界坐标系拼接）; sc2 取其一
                    val entry = scgPick.firstOrNull() ?: files.firstOrNull()
                        ?: throw IllegalStateException(L.s(R.string.e_cant_read))
                    val sc2Uri = sc2Pick.firstOrNull()?.first
                    val extraScgs = scgPick.drop(1).map { it.second to it.first }
                    val parsed = parseScgForDialog(context, entry.first, sc2Uri, entry.second, extraScgs)
                    scgGroups = parsed.groups
                    parts = ScgConverter.extractUvParts(parsed.groups)
                    if (parts.isEmpty()) throw IllegalArgumentException(L.s(R.string.e_obj_parse))
                }
                parseError = null
            } catch (e: Exception) {
                parseError = e.message ?: L.s(R.string.e_obj_parse)
            } finally {
                loading = false
            }
        }
    }

    /** 部件下标 -> 3D 组 id: SCG 用组 id, OBJ 用下标 */
    fun gidFor(t: Int?): Long? = when {
        t == null -> null
        scgGroups != null -> scgGroups!!.getOrNull(t)?.id
        else -> t.toLong()
    }

    // ===== 贴图导入（PNG/PVR/DDS, .dvpl 自动解包; 给单个部件或全局, 两视图同步） =====
    val texPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = texTarget
        texTarget = null
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException(L.s(R.string.e_cant_read))
                    val data = if (DvplCodec.isDvplFile(raw))
                        try { DvplCodec.decode(raw) } catch (e: Exception) { raw } else raw
                    var bmp: Bitmap? = null
                    if (PvrConverter.isPvrFile(data)) bmp = PvrConverter.decodeToBitmap(data)
                    else if (DdsConverter.isDdsFile(data)) bmp = DdsConverter.decodeToBitmap(data)?.first
                    else bmp = BitmapFactory.decodeByteArray(data, 0, data.size,
                        BitmapFactory.Options().apply { inPremultiplied = false })
                    if (bmp == null) throw IllegalArgumentException(L.s(R.string.e_uv_tex))
                    // 超大图降采样（GL 上传 + 显存占用）
                    val maxSide = 4096
                    if (maxOf(bmp.width, bmp.height) > maxSide) {
                        val sc = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
                        val nb = PvrConverter.resampleBitmap(bmp,
                            (bmp.width * sc).toInt().coerceAtLeast(1),
                            (bmp.height * sc).toInt().coerceAtLeast(1))
                        bmp.recycle()
                        bmp = nb
                    }
                    val b = bmp
                    withContext(Dispatchers.Main) {
                        // 旧贴图不显式 recycle、交给 GC: 渲染线程的上传队列或正在执行的
                        // texImage2D 可能还引用着它, 途中被 recycle 会让 GLUtils 抛异常、
                        // 纹理变空数据 → 该部件贴图填充层画不出来（线框还在）
                        texMap.put(target, b)
                        // 两个视图都发: 2D/3D 视图常驻互切后不再重建, 只发活动视图的话,
                        // 在对方模式下导入的贴图永远到不了另一视图（贴图模式只见棋盘底）
                        view3d.value?.setTexture(gidFor(target), b)
                        view.value?.setTexture(target, b)
                    }
                } catch (e: Exception) {
                    texError = e.message ?: L.s(R.string.e_uv_tex)
                }
            }
        }
    }

    // ===== 有效可见集（复选框 ∩ LOD 过滤） =====
    val effVisible: Set<Int>? = remember(parts, hidden, lod0Only) {
        if (hidden.isEmpty() && !lod0Only) null
        else parts.indices.filter { it !in hidden && (!lod0Only || parts[it].lod == 0) }.toSet()
    }

    // 3D 视图: 实心组 = 可见组; 点选部件独显
    val groups = scgGroups
    val isObj = groups == null && parts.isNotEmpty()
    val has3d = groups != null || parts.any { it.pos != null }
    val visibleGids: Set<Long> = remember(effVisible, groups, isObj, parts.size) {
        when {
            groups != null ->
                if (effVisible == null) groups.map { it.id }.toSet()
                else effVisible.mapNotNull { i -> groups.getOrNull(i)?.id }.toSet()
            isObj -> (effVisible ?: parts.indices.toSet()).map { it.toLong() }.toSet()
            else -> emptySet()
        }
    }
    val soloGid = soloIdx?.let { i ->
        if (isObj) i.toLong() else groups?.getOrNull(i)?.id
    }

    DisposableEffect(Unit) {
        onDispose { texMap.values.forEach { it.recycle() }; texMap.clear() }
    }

    Column(Modifier.fillMaxSize()) {
        // ===== 顶栏 =====
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
            }
            Column(Modifier.weight(1f)) {
                val title = files.first().second +
                    (if (files.size > 1) "  +" + (files.size - 1) else "")
                Text(title, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (parts.isNotEmpty()) {
                    Text(
                        L.s(R.string.uv_parts) + " " + parts.size +
                            (if (texError != null) " - " + texError else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }

        // ===== 模式行: 2D 三模式 + 3D 预览（SCG 输入时） =====
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            FilterChip(selected = mode == UvGlView.MODE_WIREFRAME && !show3d,
                onClick = { show3d = false; mode = UvGlView.MODE_WIREFRAME },
                label = { Text(L.s(R.string.uv_wireframe)) })
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = mode == UvGlView.MODE_TEXTURE && !show3d,
                onClick = { show3d = false; mode = UvGlView.MODE_TEXTURE },
                label = { Text(L.s(R.string.uv_textured)) })
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = mode == UvGlView.MODE_OVERLAY && !show3d,
                onClick = { show3d = false; mode = UvGlView.MODE_OVERLAY },
                label = { Text(L.s(R.string.uv_overlay)) })
            Spacer(Modifier.width(4.dp))
            if (has3d) {
                FilterChip(selected = show3d, onClick = { show3d = !show3d; if (show3d) ever3d = true },
                    label = { Text("3D") })
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { view.value?.resetView() }) {
                Icon(Icons.Default.CenterFocusWeak, contentDescription = L.s(R.string.uv_reset))
            }
        }

        // ===== 画布区: 2D UV 图 / 3D 贴图预览 =====
        // 2D 视图加载完成后常驻; 3D 视图首次进入后常驻。切换只改 View.visibility:
        // TextureView 的 SurfaceTexture 不随 GONE 销毁, 按需渲染线程空转零开销,
        // 切回瞬间即有内容。深色底对齐 GL 清屏色, 首次创建的首帧空档不再透白。
        Box(Modifier.fillMaxWidth().weight(1f).background(UvCanvasBg)) {
            if (!loading && parseError == null && parts.isNotEmpty()) {
                AndroidView(
                    factory = { ctx ->
                        UvGlView(ctx).also {
                            view.value = it
                            it.setParts(parts)
                            for ((k, b) in texMap) it.setTexture(k, b)
                        }
                    },
                    update = { v ->
                        v.visibility = if (show3d) View.GONE else View.VISIBLE
                        v.setMode(mode)
                        v.setFilter(nearest)
                        v.setVisibility(effVisible)
                        v.highlight = soloIdx ?: -1
                    },
                    onRelease = { it.onPause(); view.value = null },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (ever3d && has3d) {
                AndroidView(
                    factory = { ctx ->
                        ScgGlView(ctx).also {
                            view3d.value = it
                            if (isObj) {
                                // OBJ: 位置汤 + 屏幕空间 UV 汤
                                it.setObjScene(parts.mapNotNull { p ->
                                    p.pos?.let { ps ->
                                        ScgGlView.ObjPart(p.name, ps, p.uv, p.tris)
                                    }
                                })
                            } else {
                                it.setScene(groups!!)
                            }
                            // 视图重建后按登记表重挂贴图
                            for ((k, b) in texMap) it.setTexture(gidFor(k), b)
                        }
                    },
                    update = { v ->
                        v.visibility = if (show3d) View.VISIBLE else View.GONE
                        v.setDisplay(visibleGids, soloGid)
                    },
                    onRelease = { it.onPause(); view3d.value = null },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (parseError != null) {
                Text(parseError ?: "", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp))
            } else {
                Text(
                    if (show3d) L.s(R.string.uv_hint_3d) else L.s(R.string.uv_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
        }

        // ===== 滤镜/LOD/导入行 =====
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            FilterChip(selected = nearest, onClick = { nearest = !nearest },
                label = { Text(L.s(R.string.uv_filter_nearest)) })
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = lod0Only, onClick = { lod0Only = !lod0Only },
                label = { Text(L.s(R.string.uv_lod0)) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                texTarget = null
                texPicker.launch(arrayOf("image/*", "application/octet-stream"))
            }) {
                Icon(Icons.Default.Image, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(L.s(R.string.uv_import_all))
            }
        }

        // ===== 部件显隐批量控制: 默认全显; 反向筛选 = 全不选后勾想要的 =====
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Text(
                L.s(R.string.scg_selected, parts.size - hidden.size, parts.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            // 批量导出: 勾选 ∩ LOD0 过滤后的部件贴图区域, 每部件一个 PNG
            TextButton(onClick = {
                val snap = texMap.toMap()
                val vis = effVisible
                scope.launch {
                    try {
                        val n = withContext(Dispatchers.IO) {
                            exportVisibleTextures(context, parts, vis, snap)
                        }
                        val msg = if (n > 0) L.s(R.string.x_saved_batch, n)
                        else L.s(R.string.e_no_tex_parts)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        texError = e.message ?: L.s(R.string.e_uv_tex)
                    }
                }
            }) {
                Text(L.s(R.string.uv_export_sel), style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { hidden = emptySet() }) {
                Text(L.s(R.string.scg_sel_all), style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { hidden = parts.indices.toSet() }) {
                Text(L.s(R.string.scg_sel_none), style = MaterialTheme.typography.labelMedium)
            }
        }

        // ===== 部件列表: 点击 2D=飞行+高亮 / 3D=独显; 图标=单独导入贴图 =====
        LazyColumn(Modifier.fillMaxWidth().height(190.dp)) {
            itemsIndexed(parts, key = { i, _ -> i }) { i, p ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable {
                            // 点部件 = 选中: 2D 独显(只画该件) / 3D 独显(solo); 再点取消
                            if (!show3d && p.hasUv) view.value?.flyTo(p.bbox)
                            soloIdx = if (soloIdx == i) null else i
                        }
                        .padding(horizontal = 4.dp)
                ) {
                    Checkbox(checked = i !in hidden,
                        onCheckedChange = { checked ->
                            hidden = if (checked) hidden - i else hidden + i
                        })
                    Text(
                        (p.name ?: "#" + i) + (if (p.lod > 0) "  LOD" + p.lod else "") +
                            (if (soloIdx == i) "  ▲" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (p.tris > 0) {
                        Text(p.tris.toString() + "△",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (!p.hasUv) {
                        Text(L.s(R.string.uv_no_uv), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    } else {
                        IconButton(onClick = {
                            texTarget = i
                            texPicker.launch(arrayOf("image/*", "application/octet-stream"))
                        }) {
                            Icon(Icons.Default.GridView,
                                contentDescription = L.s(R.string.uv_import_part),
                                modifier = Modifier.size(18.dp))
                        }
                        // 导出该部件贴图区域（生效贴图 = 本部件导入的, 否则全局的）
                        val effectiveTex = texMap[i] ?: texMap[null]
                        if (effectiveTex != null) {
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        val saved = withContext(Dispatchers.IO) {
                                            exportPartTexture(context, p, i, effectiveTex)
                                        }
                                        Toast.makeText(context,
                                            L.s(R.string.x_saved, saved), Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        texError = e.message ?: L.s(R.string.e_uv_tex)
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Save,
                                    contentDescription = L.s(R.string.uv_export_tex),
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 导出文件名: 部件名清洗 + 部件下标（部件名可能重复） */
private fun exportTexName(part: UvPart, idx: Int): String {
    val safe = (part.name ?: "part").replace(Regex("[^A-Za-z0-9_.]"), "_")
        .trim('_').take(48).ifEmpty { "part" }
    return safe + "_" + idx + ".png"
}

/**
 * UV 三角形汤 → 覆盖纹素掩码: 边函数光栅化（像素中心, 在边上也算覆盖）。
 * V 向上、位图行 0 在上 → y = (1-v)*h。
 *
 * 平铺（履带等 UV 越界反复平铺）按 REPEAT 语义折回主 [0,1] 块, 但只能按
 * 三角形整体平移 + 写入时取模环绕, 不能逐顶点 fract: u/v 正好落在整数边界
 * (1.0 极常见——贴图岛边缘) 时 fract 会把它折成 0, 贴边/跨块的三角形直接
 * 被拉变形（顶边覆盖跑到底边去）。
 */
internal fun rasterizeMask(uv: FloatArray, w: Int, h: Int): ByteArray {
    val mask = ByteArray(w * h)
    val n = uv.size / 6
    for (t in 0 until n) {
        val o = t * 6
        val au = uv[o]; val av = uv[o + 1]
        val bu = uv[o + 2]; val bv = uv[o + 3]
        val cu = uv[o + 4]; val cv = uv[o + 5]
        // 整体平移到主块起点（保形）, 越界部分靠写入取模环绕落回主块
        val su = kotlin.math.floor(kotlin.math.min(au, kotlin.math.min(bu, cu)))
        val sv = kotlin.math.floor(kotlin.math.min(av, kotlin.math.min(bv, cv)))
        var x0 = (au - su) * w; var y0 = (1f - (av - sv)) * h
        var x1 = (bu - su) * w; var y1 = (1f - (bv - sv)) * h
        var x2 = (cu - su) * w; var y2 = (1f - (cv - sv)) * h
        // 统一绕向; 有向面积 0 = 退化三角形, 跳过
        val a2 = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (a2 == 0f) continue
        if (a2 < 0f) { val tx = x1; x1 = x2; x2 = tx; val ty = y1; y1 = y2; y2 = ty }
        // 三角形像素 bbox（±1 余量; 不夹到图内, 环绕由写入取模完成）
        val minX = kotlin.math.floor(kotlin.math.min(x0, kotlin.math.min(x1, x2))).toInt() - 1
        val maxX = kotlin.math.ceil(kotlin.math.max(x0, kotlin.math.max(x1, x2))).toInt() + 1
        val minY = kotlin.math.floor(kotlin.math.min(y0, kotlin.math.min(y1, y2))).toInt() - 1
        val maxY = kotlin.math.ceil(kotlin.math.max(y0, kotlin.math.max(y1, y2))).toInt() + 1
        // 垃圾 UV 防护: 单个三角形扫描面积超过贴图 64 倍直接跳过
        if ((maxX - minX + 1).toLong() * (maxY - minY + 1) > 64L * w * h) continue
        for (py in minY..maxY) {
            val pcy = py + 0.5f
            val row = Math.floorMod(py, h) * w
            var xx = Math.floorMod(minX, w)
            var px = minX
            while (px <= maxX) {
                val pcx = px + 0.5f
                if ((x1 - x0) * (pcy - y0) - (y1 - y0) * (pcx - x0) >= 0f &&
                    (x2 - x1) * (pcy - y1) - (y2 - y1) * (pcx - x1) >= 0f &&
                    (x0 - x2) * (pcy - y2) - (y0 - y2) * (pcx - x2) >= 0f
                ) mask[row + xx] = 1
                px++
                xx++; if (xx == w) xx = 0
            }
        }
    }
    return mask
}

/** 3×3 膨胀（水平/垂直两趟分离实现）: 像素中心判定会漏细边缘纹素, 补一圈 */
private fun dilateMask(mask: ByteArray, w: Int, h: Int) {
    val tmp = ByteArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            if (mask[row + x] != 0.toByte()) {
                tmp[row + x] = 1
                if (x > 0) tmp[row + x - 1] = 1
                if (x < w - 1) tmp[row + x + 1] = 1
            }
        }
    }
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            if (tmp[row + x] != 0.toByte()) {
                mask[row + x] = 1
                if (y > 0) mask[row - w + x] = 1
                if (y < h - 1) mask[row + w + x] = 1
            }
        }
    }
}

/**
 * 部件贴图（保持原图分辨率与原位）: 与源贴图同尺寸, 仅 UV 覆盖的纹素保留
 * 原图像素, 其余一律透明。导出的 PNG 可在图像编辑器里与原图像素级对位叠加。
 * 纯像素域 getPixels/setPixels——不走 Canvas（直通 alpha 位图在部分 ROM 上
 * 会抛 "canvas: trying to use a non-premultiplied bitmap", 同
 * PvrConverter.resampleBitmap 注释里的坑）。
 */
private fun maskedFullBitmap(bmp: Bitmap, uv: FloatArray): Bitmap {
    val w = bmp.width; val h = bmp.height
    val mask = rasterizeMask(uv, w, h)
    dilateMask(mask, w, h)
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) if (mask[i] == 0.toByte()) px[i] = 0   // 全透明
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.isPremultiplied = bmp.isPremultiplied   // 保持直通/预乘属性, PNG 编码按属性换算
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

/**
 * 导出部件贴图 PNG（原位透明版, 同原图分辨率）。
 * 保存到用户设置的导出目录, 未设置则公共下载目录。
 */
private fun exportPartTexture(context: Context, part: UvPart, idx: Int, bmp: Bitmap): String {
    val bytes = java.io.ByteArrayOutputStream().use { o ->
        maskedFullBitmap(bmp, part.uv).compress(Bitmap.CompressFormat.PNG, 100, o); o.toByteArray()
    }
    val dirUri = Prefs.getOutputDirUri(context)
    val outName = exportTexName(part, idx)
    return if (dirUri != null) saveToDir(context, dirUri, outName, bytes)
    else saveToDownloads(context, outName, bytes, null)
}

/**
 * 批量导出当前显示部件的贴图（勾选 ∩ LOD0 过滤 = 2D 视图正显示着的部件）。
 * 每个部件一个同分辨率原位 PNG（部件名_下标.png, 重名自动加序号）。
 * @return 导出个数
 */
private fun exportVisibleTextures(
    context: Context,
    parts: List<UvPart>,
    vis: Set<Int>?,
    texMap: Map<Int?, Bitmap>
): Int {
    val dirUri = Prefs.getOutputDirUri(context)
    var n = 0
    for (i in parts.indices) {
        if (vis != null && i !in vis) continue
        val p = parts[i]
        if (!p.hasUv || p.uv.isEmpty()) continue
        val bmp = texMap[i] ?: texMap[null] ?: continue
        val bytes = java.io.ByteArrayOutputStream().use { o ->
            maskedFullBitmap(bmp, p.uv).compress(Bitmap.CompressFormat.PNG, 100, o); o.toByteArray()
        }
        val outName = exportTexName(p, i)
        if (dirUri != null) saveToDir(context, dirUri, outName, bytes)
        else saveToDownloads(context, outName, bytes, null)
        n++
    }
    return n
}