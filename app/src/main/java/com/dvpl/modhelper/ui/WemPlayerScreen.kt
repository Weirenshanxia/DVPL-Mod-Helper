package com.dvpl.modhelper.ui

import android.media.MediaPlayer
import com.dvpl.modhelper.L
import com.dvpl.modhelper.R
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dvpl.modhelper.codec.DvplCodec
import com.dvpl.modhelper.codec.WwiseConverter
import com.dvpl.modhelper.codec.WwiseNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WEM 试听屏：列表展示（含原始文件名与触发事件），点按即播。
 * Vorbis 走原生 ww2ogg 转 ogg 后 MediaPlayer 播放；PCM 直接当 wav。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WemPlayerScreen(
    files: List<Pair<Uri, String>>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 解析同选的 SoundbanksInfo.json（提供原名与触发事件）
    var info by remember {
        mutableStateOf(WwiseConverter.SoundbanksInfo(emptyMap(), emptyMap(), emptyMap()))
    }
    val jsonFiles = files.filter { it.second.contains("soundbanksinfo", ignoreCase = true) }
    // bank 文件（bnk/pck，可带 dvpl 包裹）：解析其 HIRC 事件结构，给 WEM 标注触发事件。
    // mod 重建包的媒体 ID 在 SoundbanksInfo 里查不到，事件名只能从 bank 自带的 HIRC 拿
    val bankFiles = remember(files) {
        files.filter { (_, name) ->
            val n = name.lowercase()
            !n.contains("soundbanksinfo") &&
                (n.endsWith(".bnk") || n.endsWith(".pck") ||
                    n.endsWith(".bnk.dvpl") || n.endsWith(".pck.dvpl"))
        }
    }
    val wemFiles = remember(files) {
        files.filterNot { (_, name) ->
            val n = name.lowercase()
            n.contains("soundbanksinfo") || n.endsWith(".bnk") || n.endsWith(".pck") ||
                n.endsWith(".bnk.dvpl") || n.endsWith(".pck.dvpl")
        }
    }
    // bank HIRC 解析出的 mediaId → 事件名列表
    var hircEvents by remember { mutableStateOf<Map<Long, List<String>>>(emptyMap()) }
    LaunchedEffect(files) {
        if (jsonFiles.isEmpty() && bankFiles.isEmpty()) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            var names = emptyMap<Long, String>()
            var events = emptyMap<Long, List<String>>()
            var eventNames = emptyMap<Long, String>()
            for ((uri, _) in jsonFiles) {
                try {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                    val data = if (DvplCodec.isDvplFile(raw)) DvplCodec.decode(raw) else raw
                    val parsed = WwiseConverter.parseSoundbanksInfo(data)
                    names += parsed.names
                    eventNames += parsed.eventNames
                    events = (events.keys + parsed.events.keys).associateWith { id ->
                        ((events[id] ?: emptyList()) + (parsed.events[id] ?: emptyList())).distinct()
                    }
                } catch (_: Exception) {
                }
            }
            // bank 自带 HIRC：按事件子树收集媒体（优先用 JSON 事件名，无 JSON 时按 Event 对象分组）
            val he = HashMap<Long, MutableList<String>>()
            for ((uri, _) in bankFiles) {
                try {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                    val data = if (DvplCodec.isDvplFile(raw)) DvplCodec.decode(raw) else raw
                    val bank = WwiseConverter.parse(data) ?: continue
                    val roots: Collection<Long> = if (eventNames.isNotEmpty()) eventNames.keys
                    else WwiseConverter.hircEventObjectIds(bank)
                    for ((evId, medias) in WwiseConverter.parseHircGroups(bank, roots)) {
                        val nm = eventNames[evId] ?: ("ev_" + evId)
                        for (m in medias) {
                            he.getOrPut(m) { ArrayList() }.apply { if (!contains(nm)) add(nm) }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            Pair(WwiseConverter.SoundbanksInfo(names, emptyMap(), events, eventNames), he)
        }
        info = result.first
        hircEvents = result.second
    }

    // 播放状态
    var playingIndex by remember { mutableStateOf<Int?>(null) }
    var convertingIndex by remember { mutableStateOf<Int?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    val converted = remember { mutableMapOf<Uri, File>() }
    // 音量（游戏音频多为满幅，默认 30% 防炸麦；实时调节并持久化）
    var volume by remember { mutableStateOf(com.dvpl.modhelper.Prefs.getWemVolume(context)) }

    fun stopPlayback() {
        mediaPlayer?.release()
        mediaPlayer = null
        playingIndex = null
    }

    fun togglePlay(index: Int) {
        if (playingIndex == index) {
            stopPlayback()
            return
        }
        stopPlayback()
        playError = null
        scope.launch {
            convertingIndex = index
            try {
                val (uri, _) = wemFiles[index]
                val target = converted[uri] ?: withContext(Dispatchers.IO) {
                    val wemBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException(L.s(R.string.e_cant_read))
                    // 流式截断预取检测：bnk 里只存了前几 KB，转出来只有开头一瞬
                    if (WwiseConverter.isTruncatedWem(wemBytes)) {
                        throw IllegalStateException(
                            L.s(R.string.e_trunc_listen))
                    }
                    if (WwiseConverter.isPcmWem(wemBytes)) {
                        File(context.cacheDir, "wemplay_${index}.wav").also { it.writeBytes(wemBytes) }
                    } else if (WwiseConverter.isWwiseNewPcmWem(wemBytes)) {
                        // Wwise 2021+ 新版 PCM（codec 0xFFFE）：重建标准 WAV
                        val wavBytes = WwiseConverter.wemNewPcmToWav(wemBytes)
                        File(context.cacheDir, "wemplay_${index}.wav").also { it.writeBytes(wavBytes) }
                    } else if (WwiseConverter.isPtAdpcmWem(wemBytes)) {
                        val wavBytes = WwiseConverter.decodePtAdpcmToWav(wemBytes)
                        File(context.cacheDir, "wemplay_${index}.wav").also { it.writeBytes(wavBytes) }
                    } else if (WwiseConverter.isImaAdpcmWem(wemBytes)) {
                        val wavBytes = WwiseConverter.decodeImaAdpcmToWav(wemBytes)
                        File(context.cacheDir, "wemplay_${index}.wav").also { it.writeBytes(wavBytes) }
                    } else if (WwiseConverter.isOpusWem(wemBytes)) {
                        val wavBytes = WwiseConverter.decodeOpusToWav(wemBytes, context)
                        File(context.cacheDir, "wemplay_${index}.wav").also { it.writeBytes(wavBytes) }
                    } else {
                        // 原生转换 + granule 修复（无修复播放器会认为 0 秒不发声）
                        val oggBytes = WwiseNative.convertWemToOgg(context, wemBytes)
                        File(context.cacheDir, "wemplay_${index}.ogg").also { it.writeBytes(oggBytes) }
                    }
                }.also { converted[uri] = it }
                val mp = MediaPlayer()
                mp.setDataSource(target.absolutePath)
                mp.prepare()
                mp.setVolume(volume, volume)
                mp.setOnCompletionListener { stopPlayback() }
                mp.start()
                mediaPlayer = mp
                playingIndex = index
            } catch (e: Exception) {
                playError = L.s(R.string.x_play_fail) + (e.message ?: e.javaClass.simpleName)
            } finally {
                convertingIndex = null
            }
        }
    }

    // 离开页面释放播放器
    DisposableEffect(Unit) {
        onDispose { stopPlayback() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(L.s(R.string.tpl_wem_title, wemFiles.size)) },
                navigationIcon = {
                    IconButton(onClick = {
                        stopPlayback()
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = L.s(R.string.b_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            if (wemFiles.isEmpty()) {
                Text(
                    text = L.s(R.string.x_pick_wem),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                Text(
                    text = L.s(R.string.x_tap_play) +
                        (if (jsonFiles.isNotEmpty()) L.s(R.string.x_events_from_sbi) else L.s(R.string.x_sbi_hint)) +
                        (if (bankFiles.isNotEmpty()) L.s(R.string.x_events_from_bank) else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            if (wemFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(L.s(R.string.l_volume), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = volume,
                        onValueChange = { v ->
                            volume = v
                            mediaPlayer?.setVolume(v, v)
                        },
                        onValueChangeFinished = {
                            com.dvpl.modhelper.Prefs.setWemVolume(context, volume)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    Text(
                        text = (volume * 100).toInt().toString() + "%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(40.dp)
                    )
                }
            }
            playError?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(wemFiles) { index, (uri, name) ->
                    val id = WwiseConverter.parseWemFileName(name).first
                    val origName = id?.let { info.names[it] }
                    val events = id?.let { idv ->
                        ((info.events[idv] ?: emptyList()) + (hircEvents[idv] ?: emptyList())).distinct()
                    }
                    val isPlaying = playingIndex == index
                    val isConverting = convertingIndex == index
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { togglePlay(index) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = origName?.let { "$it" } ?: name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (origName != null) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                events?.takeIf { it.isNotEmpty() }?.let { evs ->
                                    Text(
                                        text = L.s(R.string.l_events) + evs.take(3).joinToString("、") +
                                            (if (evs.size > 3) L.s(R.string.tpl_and_n_events, evs.size) else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (isConverting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                IconButton(onClick = { togglePlay(index) }) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Stop
                                        else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) L.s(R.string.b_stop) else L.s(R.string.b_play)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}