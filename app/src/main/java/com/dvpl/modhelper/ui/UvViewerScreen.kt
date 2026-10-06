package com.dvpl.modhelper.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
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
import com.dvpl.modhelper.R
import com.dvpl.modhelper.ScgGlView
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
                    }
                }
            }
        }
    }
}