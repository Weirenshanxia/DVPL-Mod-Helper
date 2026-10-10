package com.dvpl.modhelper.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dvpl.modhelper.L
import com.dvpl.modhelper.R
import com.dvpl.modhelper.codec.DvplCodec
import com.dvpl.modhelper.codec.SpriteDoc
import com.dvpl.modhelper.codec.SpriteFrame
import com.dvpl.modhelper.queryFileName
import com.dvpl.modhelper.saveToDir
import com.dvpl.modhelper.saveToDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * 精灵图编辑屏：Gfx/UI 的 txt.dvpl 描述文件 + 图集（webp）一起多选导入，
 * 图形化调整每帧的裁取矩形/逻辑画布，写回 .txt.dvpl。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpriteEditorScreen(
    files: List<Pair<Uri, String>>,
    outputDirUri: Uri?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var picked by remember { mutableStateOf(files) }
    var entries by remember { mutableStateOf<List<LoadedEntry>>(emptyList()) }
    var pairing by remember { mutableStateOf<Pairing?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var activeDoc by remember { mutableIntStateOf(0) }
    var selFrame by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(SpriteEditorView.Mode.ATLAS) }
    var docRev by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var viewInstance by remember { mutableStateOf<SpriteEditorView?>(null) }

    LaunchedEffect(picked) {
        busy = true
        loadError = null
        withContext(Dispatchers.IO) {
            try {
                val prevMap = HashMap<Uri, LoadedEntry>()
                for (en in entries) prevMap[en.uri] = en
                val loaded = classifyFiles(context, picked, prevMap)
                entries = loaded
                pairing = buildPairing(loaded)
                activeDoc = 0
                selFrame = 0
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loadError = e.message ?: e.javaClass.simpleName
            }
        }
        busy = false
    }

    // ── 当前文档/帧/图集 ──
    val p = pairing
    val docList = p?.docs ?: emptyList()
    val activeDocEntryIdx = docList.getOrNull(activeDoc) ?: -1
    val activeEntry = entries.getOrNull(activeDocEntryIdx)
    val doc = activeEntry?.doc
    val frames = doc?.frames ?: emptyList()
    val safeSel = if (frames.isEmpty()) 0 else selFrame.coerceIn(0, frames.size - 1)
    val frame = frames.getOrNull(safeSel)
    val slotIdx = frame?.atlasIdx ?: 0
    val atlasEntryIdx = p?.slots?.get(activeDocEntryIdx)?.getOrNull(slotIdx) ?: -1
    val atlasBitmap = entries.getOrNull(atlasEntryIdx)?.atlas
    val atlasName = entries.getOrNull(atlasEntryIdx)?.name
    val oob = atlasBitmap != null && frame != null &&
        frame.outsideAtlas(atlasBitmap.width, atlasBitmap.height)
    docRev  // 依赖：任何文档编辑后重算
    val anyDirty = docList.any { entries[it].doc?.isDirty == true }

    // 预览模式切换帧/文件时自动对准该帧内容，避免大图标只看到一角
    LaunchedEffect(safeSel, activeDoc, mode) {
        if (mode == SpriteEditorView.Mode.PREVIEW) viewInstance?.zoomToFrame()
    }

    fun currentDoc(): SpriteDoc? {
        val pp = pairing ?: return null
        val di = pp.docs.getOrNull(activeDoc) ?: return null
        return entries.getOrNull(di)?.doc
    }

    fun commitFrame(transform: (SpriteFrame) -> SpriteFrame) {
        val d = currentDoc() ?: return
        val f = d.frames.getOrNull(selFrame) ?: return
        d.frames[selFrame] = transform(f)
        docRev++
    }

    fun commitLog(w: Int?, h: Int?) {
        val d = currentDoc() ?: return
        val nw = (w ?: d.logW).coerceIn(1, 65536)
        val nh = (h ?: d.logH).coerceIn(1, 65536)
        d.editLogical(nw, nh)
        docRev++
    }

    fun commitName(s: String) {
        val d = currentDoc() ?: return
        val f = d.frames.getOrNull(selFrame) ?: return
        val nn = s.ifBlank { null }
        if (nn != f.name) {
            d.frames[selFrame] = f.edit(name = nn)
            docRev++
        }
    }

    fun doSave() {
        val pp = pairing ?: return
        scope.launch {
            busy = true
            status = null
            val results = ArrayList<String>()
            withContext(Dispatchers.IO) {
                var saved = 0
                for (di in pp.docs) {
                    val en = entries[di]
                    val d = en.doc ?: continue
                    if (!d.isDirty) continue
                    val bytes = d.serialize().toByteArray(Charsets.UTF_8)
                    val out = if (en.dvplType >= 0) {
                        val type = when (en.dvplType) {
                            DvplCodec.COMPRESSION_NONE -> DvplCodec.COMPRESSION_NONE
                            DvplCodec.COMPRESSION_LZ4 -> DvplCodec.COMPRESSION_LZ4
                            DvplCodec.COMPRESSION_LZ4_HC -> DvplCodec.COMPRESSION_LZ4_HC
                            DvplCodec.COMPRESSION_DEFLATE -> DvplCodec.COMPRESSION_DEFLATE
                            else -> DvplCodec.COMPRESSION_LZ4_HC  // chunked(4) 无编码器，降级
                        }
                        try {
                            DvplCodec.encode(bytes, type)
                        } catch (ex: Exception) {
                            DvplCodec.encode(bytes, DvplCodec.COMPRESSION_LZ4_HC)
                        }
                    } else {
                        bytes
                    }
                    var written = false
                    try {
                        context.contentResolver.openOutputStream(en.uri, "wt")?.use { os ->
                            os.write(out)
                            os.flush()
                            written = true
                        }
                    } catch (ex: Exception) {
                        written = false
                    }
                    if (written) {
                        d.markClean()
                        saved++
                        results.add(L.s(R.string.sprite_saved, en.name))
                    } else {
                        val name = baseName(en.name) + ".txt.dvpl"
                        try {
                            if (outputDirUri != null)
                                saveToDir(context, outputDirUri, name, out, "GfxSprites")
                            else
                                saveToDownloads(context, name, out, "GfxSprites")
                            d.markClean()
                            saved++
                            results.add(L.s(R.string.sprite_save_fallback, name))
                        } catch (ex: Exception) {
                            results.add(name + ": " + (ex.message ?: ex.javaClass.simpleName))
                        }
                    }
                }
                if (saved == 0 && results.isEmpty()) results.add(L.s(R.string.sprite_no_change))
            }
            docRev++
            status = results.joinToString("\n")
            busy = false
        }
    }

    fun doExportPng() {
        val d = currentDoc() ?: return
        val f = d.frames.getOrNull(selFrame) ?: return
        val pp = pairing ?: return
        val di = pp.docs.getOrNull(activeDoc) ?: return
        val docName = entries.getOrNull(di)?.name ?: "sprite"
        val slot = pp.slots[di]?.getOrNull(f.atlasIdx) ?: -1
        val bmp = entries.getOrNull(slot)?.atlas
        if (bmp == null) {
            status = L.s(R.string.e_sprite_no_atlas)
            return
        }
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                try {
                    val out = Bitmap.createBitmap(
                        d.logW.coerceIn(1, 4096), d.logH.coerceIn(1, 4096), Bitmap.Config.ARGB_8888
                    )
                    val c = Canvas(out)
                    val sx = max(0, f.srcX)
                    val sy = max(0, f.srcY)
                    val ex = min(bmp.width, f.srcX + f.srcW)
                    val ey = min(bmp.height, f.srcY + f.srcH)
                    if (ex > sx && ey > sy) {
                        val src = Rect(sx, sy, ex, ey)
                        val dst = RectF(
                            (f.offX + (sx - f.srcX)).toFloat(),
                            (f.offY + (sy - f.srcY)).toFloat(),
                            (f.offX + (ex - f.srcX)).toFloat(),
                            (f.offY + (ey - f.srcY)).toFloat()
                        )
                        c.drawBitmap(bmp, src, dst, null)
                    }
                    val bos = ByteArrayOutputStream()
                    out.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    out.recycle()
                    val png = bos.toByteArray()
                    val name = baseName(docName) +
                        (if (d.frames.size > 1) "_frame" + selFrame else "") + ".png"
                    if (outputDirUri != null)
                        saveToDir(context, outputDirUri, name, png, "GfxSprites")
                    else
                        saveToDownloads(context, name, png, "GfxSprites")
                    status = L.s(R.string.sprite_png_done, name)
                } catch (e: Exception) {
                    status = e.message ?: e.javaClass.simpleName
                }
            }
            busy = false
        }
    }

    fun doReset() {
        val pp = pairing ?: return
        val di = pp.docs.getOrNull(activeDoc) ?: return
        val en = entries.getOrNull(di) ?: return
        val text = en.originalText ?: return
        try {
            val fresh = SpriteDoc.parse(text)
            val list = ArrayList(entries)
            list[di] = LoadedEntry(en.uri, en.name, fresh, en.atlas, en.dvplType, en.originalText)
            entries = list
            docRev++
            status = null
        } catch (e: Exception) {
            status = e.message ?: e.javaClass.simpleName
        }
    }

    // ── 补选文件（图集或 txt 一起） ──
    val addFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            picked = picked + uris.map { Pair(it, queryFileName(context, it)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (frame?.name ?: ("#" + safeSel)) + if (anyDirty) " •" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewInstance?.fitView() },
                        enabled = !busy && pairing != null
                    ) { Icon(Icons.Default.FitScreen, contentDescription = null) }
                    IconButton(
                        onClick = { viewInstance?.zoomToFrame() },
                        enabled = !busy && frame != null
                    ) { Icon(Icons.Default.CenterFocusStrong, contentDescription = null) }
                    TextButton(
                        onClick = {
                            mode = if (mode == SpriteEditorView.Mode.ATLAS)
                                SpriteEditorView.Mode.PREVIEW
                            else
                                SpriteEditorView.Mode.ATLAS
                        },
                        enabled = !busy && pairing != null
                    ) {
                        Text(
                            if (mode == SpriteEditorView.Mode.ATLAS)
                                L.s(R.string.sprite_mode_preview)
                            else
                                L.s(R.string.sprite_mode_atlas)
                        )
                    }
                    IconButton(
                        onClick = { doReset() },
                        enabled = !busy && (currentDoc()?.isDirty == true)
                    ) { Icon(Icons.Default.Refresh, contentDescription = null) }
                    IconButton(
                        onClick = { doSave() },
                        enabled = anyDirty && !busy
                    ) { Icon(Icons.Default.Save, contentDescription = null) }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(pad)
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                loadError != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = loadError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { picked = picked.toList() }) {
                            Text(L.s(R.string.sprite_retry))
                        }
                    }
                }
                p == null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) { CircularProgressIndicator() }
                }
                docList.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = L.s(R.string.e_sprite_no_txt),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { addFilesLauncher.launch(arrayOf("*/*")) }) {
                            Text(L.s(R.string.sprite_pick_atlas))
                        }
                    }
                }
                doc == null -> {}
                else -> {
                    AndroidView(
                        factory = { ctx ->
                            SpriteEditorView(ctx).apply {
                                listener = object : SpriteEditorView.Listener {
                                    override fun onFrameSelected(index: Int) {
                                        selFrame = index
                                    }

                                    override fun onFrameEdited(index: Int, f: SpriteFrame) {
                                        val d = currentDoc() ?: return
                                        if (index in d.frames.indices) {
                                            d.frames[index] = f
                                            docRev++
                                        }
                                    }

                                    override fun onLogicalEdited(w: Int, h: Int) {
                                        val d = currentDoc() ?: return
                                        d.editLogical(w.coerceIn(1, 65536), h.coerceIn(1, 65536))
                                        docRev++
                                    }
                                }
                                viewInstance = this
                            }
                        },
                        update = { v ->
                            docRev  // 依赖：任何编辑后重推数据
                            v.setMode(mode)
                            v.setAtlas(atlasBitmap)
                            v.setDocData(frames, doc.logW, doc.logH, safeSel)
                            v.placeholderText = L.s(R.string.e_sprite_no_atlas)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    Surface(
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                    if (docList.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            items(docList.size) { i ->
                                FilterChip(
                                    selected = i == activeDoc,
                                    onClick = {
                                        activeDoc = i
                                        selFrame = 0
                                    },
                                    label = { Text(entries[docList[i]].name, maxLines = 1) }
                                )
                            }
                        }
                    }
                    if (frames.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            items(frames.size) { i ->
                                FilterChip(
                                    selected = i == safeSel,
                                    onClick = { selFrame = i },
                                    label = { Text(frames[i].name ?: "#i" + i, maxLines = 1) }
                                )
                            }
                        }
                    }

                    Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                        Text(
                            text = buildString {
                                append(atlasName ?: L.s(R.string.e_sprite_no_atlas))
                                atlasBitmap?.let {
                                    append(" · ").append(it.width).append('×').append(it.height)
                                }
                                append(" · ").append(doc.logW).append('×').append(doc.logH)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (oob) {
                            Text(
                                text = L.s(R.string.sprite_oob),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    val missing = doc.atlasNames.indices.filter {
                        (p.slots[activeDocEntryIdx]?.getOrNull(it) ?: -1) < 0
                    }
                    if (missing.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = L.s(
                                    R.string.sprite_missing_atlas,
                                    missing.joinToString { doc.atlasNames[it] }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { addFilesLauncher.launch(arrayOf("*/*")) }) {
                                Text(L.s(R.string.sprite_pick_atlas))
                            }
                        }
                    }

                    if (frame != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            NumberField(L.s(R.string.sprite_src_x), frame.srcX) { v ->
                                commitFrame { it.edit(srcX = v) }
                            }
                            NumberField(L.s(R.string.sprite_src_y), frame.srcY) { v ->
                                commitFrame { it.edit(srcY = v) }
                            }
                            NumberField(L.s(R.string.sprite_src_w), frame.srcW) { v ->
                                commitFrame { it.edit(srcW = v.coerceAtLeast(1)) }
                            }
                            NumberField(L.s(R.string.sprite_src_h), frame.srcH) { v ->
                                commitFrame { it.edit(srcH = v.coerceAtLeast(1)) }
                            }
                            NumberField(L.s(R.string.sprite_off_x), frame.offX) { v ->
                                commitFrame { it.edit(offX = v) }
                            }
                            NumberField(L.s(R.string.sprite_off_y), frame.offY) { v ->
                                commitFrame { it.edit(offY = v) }
                            }
                            NumberField(L.s(R.string.sprite_canvas_w), doc.logW) { v -> commitLog(v, null) }
                            NumberField(L.s(R.string.sprite_canvas_h), doc.logH) { v -> commitLog(null, v) }
                            NameField(frame.name) { s -> commitName(s ?: "") }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        OutlinedButton(
                            onClick = { doExportPng() },
                            enabled = !busy && frame != null && atlasBitmap != null
                        ) { Text(L.s(R.string.sprite_export_png)) }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = status ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 私有组件 ─────────────────────────

@Composable
private fun NumberField(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            s.trim().toIntOrNull()?.let { v ->
                if (v != value) onCommit(v)
            }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.widthIn(min = 84.dp)
    )
}

@Composable
private fun NameField(
    value: String?,
    onCommit: (String?) -> Unit
) {
    var text by remember(value) { mutableStateOf(value ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            val nn = s.ifBlank { null }
            if (nn != value) onCommit(nn)
        },
        label = { Text(L.s(R.string.sprite_frame_name)) },
        singleLine = true,
        modifier = Modifier.widthIn(min = 120.dp)
    )
}

// ───────────────────────── 数据与加载 ─────────────────────────

/** 一个已载入文件：精灵 txt 或图集位图（或两者都不是） */
private class LoadedEntry(
    val uri: Uri,
    val name: String,
    val doc: SpriteDoc?,      // 非 null = 精灵 txt
    val atlas: Bitmap?,       // 非 null = 图集位图
    val dvplType: Int,        // -1 = 原文件非 DVPL
    val originalText: String? // txt 原始解包文本，用于重置
)

/** txt ↔ 图集 配对结果 */
private class Pairing(
    val docs: List<Int>,              // txt 文件在 entries 里的下标
    val atlases: List<Int>,           // 图集文件下标
    val slots: Map<Int, IntArray>     // txt 下标 → 每个图集槽位对应的 entries 下标（-1 缺）
)

/** 归一化文件名用于配对（去 .dvpl / .packed.webp 后缀，忽略大小写） */
private fun normName(n: String): String =
    n.removeSuffix(".dvpl").removeSuffix(".packed.webp").lowercase()

/** 读取并分类所有选中文件；prev 里已有的 uri 直接复用（保留未保存的编辑） */
private fun classifyFiles(
    context: android.content.Context,
    files: List<Pair<Uri, String>>,
    prev: Map<Uri, LoadedEntry>
): List<LoadedEntry> = files.map { (uri, name) ->
    prev[uri]?.let { return@map it }
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IOException(name)
    val isDvpl = raw.size >= 20 && DvplCodec.isDvplFile(raw)
    val dvplType = if (isDvpl) {
        try { DvplCodec.getCompressionType(raw) } catch (e: Exception) { -1 }
    } else -1
    val data = if (isDvpl) {
        try { DvplCodec.decode(raw) } catch (e: Exception) { null }
    } else raw
    val text = if (data != null) String(data, Charsets.UTF_8) else null
    val doc = if (text != null) {
        try { SpriteDoc.parse(text) } catch (e: Exception) { null }
    } else null
    val atlas = if (doc == null && data != null) {
        try { BitmapFactory.decodeByteArray(data, 0, data.size) } catch (e: Exception) { null }
    } else null
    LoadedEntry(uri, name, doc, atlas, dvplType, if (doc != null) text else null)
}

/** 按名字配对 txt 与图集；名字对不上的槽位用剩余未引用的图集按顺序补 */
private fun buildPairing(entries: List<LoadedEntry>): Pairing {
    val docs = entries.indices.filter { entries[it].doc != null }
    val atlases = entries.indices.filter { entries[it].atlas != null }
    val slots = HashMap<Int, IntArray>()
    for (di in docs) {
        val names = entries[di].doc!!.atlasNames
        val arr = IntArray(names.size) { -1 }
        for (si in names.indices) {
            val ref = normName(names[si])
            for (ti in atlases) {
                if (normName(entries[ti].name) == ref) {
                    arr[si] = ti
                    break
                }
            }
        }
        slots[di] = arr
    }
    val used = HashSet<Int>()
    for (arr in slots.values) for (v in arr) if (v >= 0) used.add(v)
    var next = 0
    outer@ for (di in docs) {
        val arr = slots[di]!!
        for (si in arr.indices) {
            if (arr[si] >= 0) continue
            while (next < atlases.size && atlases[next] in used) next++
            if (next >= atlases.size) break@outer
            arr[si] = atlases[next]
            used.add(atlases[next])
        }
    }
    return Pairing(docs, atlases, slots)
}

/** 去掉 .txt.dvpl / .dvpl 后缀，作为导出文件基名 */
private fun baseName(n: String): String = when {
    n.endsWith(".txt.dvpl", true) -> n.dropLast(8)
    n.endsWith(".dvpl", true) -> n.dropLast(5)
    else -> n
}
