package com.dvpl.modhelper.ui

import android.media.MediaPlayer
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
    val wemFiles = remember(files) { files.filterNot { it.second.contains("soundbanksinfo", ignoreCase = true) } }
    LaunchedEffect(files) {
        if (jsonFiles.isEmpty()) return@LaunchedEffect
        info = withContext(Dispatchers.IO) {
            var names = emptyMap<Long, String>()
            var events = emptyMap<Long, List<String>>()
            for ((uri, _) in jsonFiles) {
                try {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                    val data = if (DvplCodec.isDvplFile(raw)) DvplCodec.decode(raw) else raw
                    val parsed = WwiseConverter.parseSoundbanksInfo(data)
                    names += parsed.names
                    events = (events.keys + parsed.events.keys).associateWith { id ->
                        ((events[id] ?: emptyList()) + (parsed.events[id] ?: emptyList())).distinct()
                    }
                } catch (_: Exception) {
                }
            }
            WwiseConverter.SoundbanksInfo(names, emptyMap(), events)
        }
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
                        ?: throw IllegalStateException("无法读取文件")
                    // 流式截断预取检测：bnk 里只存了前几 KB，转出来只有开头一瞬
                    if (WwiseConverter.isTruncatedWem(wemBytes)) {
                        throw IllegalStateException(
                            "该 wem 是流式截断预取（不完整）——完整音频在同名 .pck 中，请从 .pck 解包后再试听")
                    }
                    if (WwiseConverter.isPcmWem(wemBytes)) {
                        File(context.cacheDir, "wemplay_${index}.wav").also { it.writeBytes(wemBytes) }
                    } else if (WwiseConverter.isPtAdpcmWem(wemBytes)) {
                        val wavBytes = WwiseConverter.decodePtAdpcmToWav(wemBytes)
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
                playError = "播放失败：" + (e.message ?: e.javaClass.simpleName)
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
                title = { Text("WEM 试听（${wemFiles.size}）") },
                navigationIcon = {
                    IconButton(onClick = {
                        stopPlayback()
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
                    text = "未选择 .wem 文件（可同时选择 SoundbanksInfo.json 显示原始文件名与触发事件）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                Text(
                    text = "点按播放 / 再点停止" + (if (jsonFiles.isNotEmpty()) "；事件名来自 SoundbanksInfo" else "；同选 SoundbanksInfo.json 可显示原始文件名与触发事件"),
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
                    Text("音量", style = MaterialTheme.typography.bodySmall)
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
                    val events = id?.let { info.events[it] }
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
                                        text = "事件：" + evs.take(3).joinToString("、") +
                                            (if (evs.size > 3) " 等${evs.size}个" else ""),
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
                                        contentDescription = if (isPlaying) "停止" else "播放"
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