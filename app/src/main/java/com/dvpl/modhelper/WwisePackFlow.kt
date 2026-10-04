package com.dvpl.modhelper

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dvpl.modhelper.codec.DvplCodec
import com.dvpl.modhelper.codec.TankParams
import com.dvpl.modhelper.codec.WwiseConverter
import com.dvpl.modhelper.codec.WwiseNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// 示例音频文件名前缀（打包时识别并忽略以此开头的文件）
const val SAMPLE_PREFIX = "__sample__"

/** 可打包的音频扩展名（wem 之外都会自动转 WEM） */
val AUDIO_EXTS = setOf("wem", "ogg", "wav", "mp3", "m4a", "aac", "flac", "opus")

// ---------- 模板目录结构 ----------

/**
 * 模板中单个可替换槽位：对应一个目标 WEM 条目。
 * folderPath = 相对于模板根目录的路径，如 "Play_Cannon/1" 或 "_unassigned/123456789"
 */
data class TemplateSlot(
    val id: Long,
    val folderPath: String,   // 文件夹路径（相对于模板根）
    val eventName: String?,   // 用于显示，null 表示无事件
    val originalName: String? // json 原始名，null 表示无
)

/** 导出结果统计 */
class TemplateResult(val folders: Int, val dirName: String)

// ---------- 文件夹打包数据结构 ----------

/** 单库打包计划 */
class PackPlan(
    val bankName: String,
    val bank: WwiseConverter.Bank,
    val wasDvpl: Boolean,
    /** 已解析的替换项（id -> wem字节），预览确认后直接执行 */
    val replacements: LinkedHashMap<Long, ByteArray>,
    /** 空文件夹（无替换音频，跳过）数量 */
    val emptySlots: Int,
    /** 自动转码数量 */
    val autoConverted: Int,
    /** 错误列表（如某文件夹多于一个音频），非空时不允许打包 */
    val errors: List<String>,
    /** 有用户音频但文件夹未匹配任何事件/ID 的路径（提示用，不阻断打包） */
    val unmatchedFolders: List<String> = emptyList(),
    /** 事件根 ID（游戏触发用，全新 ID 重建时必须保留；来自 JSON 事件 Id ∩ bank 对象） */
    val protectedIds: Set<Long> = emptySet()
)

// ---------- 辅助函数 ----------

/** 收集所选 SoundbanksInfo.json（自动解 DVPL 包裹，多个合并）。
 *  用户未提供任何 json 时，自动从已安装游戏包 assets 提取（与 TankParams 同模式，
 *  路径 Data/WwiseSound/SoundbanksInfo.json，实测 97 bank/1826 事件全覆盖）。 */
suspend fun collectSoundbanksInfo(context: Context, jsons: List<Uri>): WwiseConverter.SoundbanksInfo {
    var info = WwiseConverter.SoundbanksInfo(emptyMap(), emptyMap(), emptyMap())
    for (u in jsons) {
        try {
            val raw = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: continue
            val unwrapped = if (DvplCodec.isDvplFile(raw)) DvplCodec.decode(raw) else raw
            val parsed = WwiseConverter.parseSoundbanksInfo(unwrapped)
            // 与单 JSON 内部 walkJson 的"先来者优先"语义保持一致：
            // Kotlin Map + 后者优先，故把已累计的 info 放右边覆盖新解析结果
            info = WwiseConverter.SoundbanksInfo(
                parsed.names + info.names, parsed.dirs + info.dirs,
                (parsed.events.keys + info.events.keys).associateWith { id ->
                    ((parsed.events[id] ?: emptyList()) + (info.events[id] ?: emptyList())).distinct()
                },
                parsed.eventNames + info.eventNames
            )
        } catch (e: Exception) {
            android.util.Log.w("WwisePack", L.s(R.string.e_sbi_parse) + (u.lastPathSegment ?: ""), e)
        }
    }
    // 自动提取兜底：用户没选 json（或全都解析失败）时读游戏包内建表
    if (info.names.isEmpty() && info.eventNames.isEmpty()) {
        gameSoundbanksInfo(context)?.let { return it }
    }
    return info
}

/** 游戏包内建 SoundbanksInfo.json（约 3.4MB，解析结果缓存常驻几 MB，可接受） */
private var gameInfoCache: WwiseConverter.SoundbanksInfo? = null

private fun gameSoundbanksInfo(context: Context): WwiseConverter.SoundbanksInfo? {
    gameInfoCache?.let { return it }
    for (pkg in TankParams.GAME_PKGS) {
        try {
            val am = context.packageManager.getResourcesForApplication(pkg).assets
            val parsed = am.open("Data/WwiseSound/SoundbanksInfo.json").use { s ->
                val raw = s.readBytes()
                // 实测为裸 JSON；万一某版本 DVPL 包裹也能解
                val payload = if (DvplCodec.isDvplFile(raw)) DvplCodec.decode(raw) else raw
                WwiseConverter.parseSoundbanksInfo(payload)
            }
            if (parsed.names.isNotEmpty() || parsed.eventNames.isNotEmpty()) {
                gameInfoCache = parsed
                return parsed
            }
        } catch (e: Exception) { /* 包不存在 / 无此文件，跳过 */ }
    }
    return null
}

/** packed_codebooks 只读一次（文件级 lazy 缓存，约 74KB 常驻），避免批量转码时每文件重复读 assets */
private val packedCodebooks by lazy {
    AppCtx.app.assets.open("packed_codebooks_aoTuV_603.bin").use { it.readBytes() }
}

/** 任意音频 Uri -> 游戏可用 WEM 字节 */
suspend fun encodeAnyToWem(context: Context, uri: Uri): ByteArray {
    val pcmResult = decodeToPcm(context, uri)
    val oggBytes = WwiseNative.pcmToOgg(pcmResult.pcm, pcmResult.sampleRate, pcmResult.channels)
        ?: throw IllegalStateException(L.s(R.string.e_vorbis_enc))
    val packed = packedCodebooks
    val wemBytes = WwiseConverter.oggToWem(oggBytes, packed)
    val problem = WwiseConverter.validateWemStructure(wemBytes)
    if (problem != null) throw IllegalStateException(L.s(R.string.e_selfcheck) + problem)
    return wemBytes
}

/**
 * WEM -> 可直接播放的音频（按实际编码格式分支）。
 * 返回 Pair<字节, 文件后缀>，后缀含点号如 ".ogg" 或 ".wav"。
 * 截断预取的 WEM（来自 BNK 副本）返回 null。
 */
fun wemToPlayableAudio(context: Context, wemBytes: ByteArray): Pair<ByteArray, String>? {
    if (WwiseConverter.isTruncatedWem(wemBytes)) return null
    return try {
        when {
            WwiseConverter.isPcmWem(wemBytes) ->
                Pair(wemBytes, ".wav")
            WwiseConverter.isWwiseNewPcmWem(wemBytes) ->
                // Wwise 2021+ 新版 PCM（codec 0xFFFE）：重建标准 WAV
                Pair(WwiseConverter.wemNewPcmToWav(wemBytes), ".wav")
            WwiseConverter.isPtAdpcmWem(wemBytes) ->
                Pair(WwiseConverter.decodePtAdpcmToWav(wemBytes), ".wav")
            WwiseConverter.isImaAdpcmWem(wemBytes) ->
                Pair(WwiseConverter.decodeImaAdpcmToWav(wemBytes), ".wav")
            WwiseConverter.isOpusWem(wemBytes) ->
                Pair(WwiseConverter.decodeOpusToWav(wemBytes, context), ".wav")
            else ->
                Pair(WwiseNative.convertWemToOgg(context, wemBytes), ".ogg")
        }
    } catch (e: Exception) {
        android.util.Log.w("WwiseTpl", "wemToPlayableAudio failed", e)
        null
    }
}

/**
 * HIRC 兜底分组：mediaId → 事件文件夹名。
 *
 * SoundbanksInfo 与 bank 版本错配（如整库重建的语音 mod，媒体 id 全新、
 * JSON 查不到）时，用 bank 内置 HIRC 事件结构分组：
 * - 有 JSON：事件 id 与 JSON 事件名对上 → 用可读事件名（事件 id 是事件名哈希，
 *   mod 重建通常保留原名，所以旧 JSON 的名字也能用）
 * - 无 JSON：按 HIRC Event 对象分组，文件夹名 ev_{id}
 */
private fun hircMediaFolders(bank: WwiseConverter.Bank, info: WwiseConverter.SoundbanksInfo): Map<Long, String> {
    if (bank.hircSize <= 0) return emptyMap()
    val roots: Collection<Long> = if (info.eventNames.isNotEmpty()) info.eventNames.keys
    else WwiseConverter.hircEventObjectIds(bank)
    if (roots.isEmpty()) return emptyMap()
    val out = LinkedHashMap<Long, String>()
    for ((evId, medias) in WwiseConverter.parseHircGroups(bank, roots)) {
        val name = info.eventNames[evId] ?: ("ev_" + evId)
        for (m in medias) out.putIfAbsent(m, name)
    }
    return out
}

/**
 * 由 bank + SoundbanksInfo 构建模板槽位列表。
 *
 * 规则：
 * - 每个条目找到其触发事件列表中第一个事件名作为文件夹名
 * - JSON 查不到的条目走 HIRC 兜底分组（bank 内置事件结构）
 * - 同一事件名对应多个 ID 时，按顺序编号子文件夹 1/ 2/ ...
 * - 仍无归属的条目放 _unassigned/{id}/
 */
fun buildTemplateSlots(bank: WwiseConverter.Bank, info: WwiseConverter.SoundbanksInfo): List<TemplateSlot> {
    val byEvent = LinkedHashMap<String, MutableList<WwiseConverter.WemEntry>>()
    val unassigned = ArrayList<WwiseConverter.WemEntry>()

    for (e in bank.entries) {
        val events = info.events[e.id]
        val firstEvent = events?.firstOrNull { it.isNotBlank() }
        if (firstEvent != null) {
            byEvent.getOrPut(firstEvent) { ArrayList() }.add(e)
        } else {
            unassigned.add(e)
        }
    }

    // HIRC 兜底：JSON 没覆盖的条目按 bank 内置事件结构重新分组
    if (unassigned.isNotEmpty()) {
        val hirc = hircMediaFolders(bank, info)
        if (hirc.isNotEmpty()) {
            val still = ArrayList<WwiseConverter.WemEntry>()
            for (e in unassigned) {
                val folder = hirc[e.id]
                if (folder != null) {
                    byEvent.getOrPut(folder) { ArrayList() }.add(e)
                } else {
                    still.add(e)
                }
            }
            unassigned.clear()
            unassigned.addAll(still)
        }
    }

    val slots = ArrayList<TemplateSlot>()
    for ((event, entries) in byEvent) {
        val safeName = sanitizeFolderName(event)
        if (entries.size == 1) {
            val e = entries[0]
            slots.add(TemplateSlot(e.id, safeName, event, info.names[e.id]))
        } else {
            entries.forEachIndexed { idx, e ->
                slots.add(TemplateSlot(e.id, safeName + "/" + (idx + 1), event, info.names[e.id]))
            }
        }
    }
    for (e in unassigned) {
        slots.add(TemplateSlot(e.id, "_unassigned/" + e.id, null, info.names[e.id]))
    }
    return slots
}

/** 将事件名转为合法文件夹名（去掉 Windows/Android 不允许的字符，保留中文） */
fun sanitizeFolderName(name: String): String {
    val sb = StringBuilder()
    for (c in name) {
        if (c == '\\' || c == '/' || c == ':' || c == '|' || c == '*' || c == '?' ||
            c == '"' || c == '<' || c == '>') {
            sb.append('_')
        } else {
            sb.append(c)
        }
    }
    return sb.toString().trim().ifEmpty { "_unnamed" }
}

// ---------- 模板导出 ----------

/**
 * 导出打包模板：按事件名建文件夹，每个文件夹内放示例音频（可选）+ READ_ME.txt。
 * 示例文件名：__sample__.ogg 或 __sample__.wav（按实际格式）。
 * 顶层目录：
 *   <baseName>_<库名>/
 *     Play_Cannon_Fire/
 *       __sample__.ogg
 *     Play_Engine/
 *       1/
 *         __sample__.ogg
 *       2/
 *         __sample__.ogg
 *     _unassigned/
 *       123456789/
 *         __sample__.ogg
 *     READ_ME.txt
 */
suspend fun exportPackTemplate(
    context: Context,
    bankSels: List<Pair<Uri, String>>,
    info: WwiseConverter.SoundbanksInfo,
    includeAudio: Boolean,
    dirUri: Uri?,
    baseName: String
): List<TemplateResult> = withContext(Dispatchers.IO) {
    val results = ArrayList<TemplateResult>()
    for ((bankUri, bankName) in bankSels) {
        currentCoroutineContext().ensureActive()
        val input = context.contentResolver.openInputStream(bankUri)?.use { it.readBytes() }
            ?: throw IllegalStateException(L.s(R.string.e_cant_read_name) + bankName)
        val data = if (DvplCodec.isDvplFile(input)) DvplCodec.decode(input) else input
        val bank = WwiseConverter.parse(data) ?: throw IllegalStateException(L.s(R.string.e_not_pck))
        val stem = bankName.removeSuffix(".dvpl").substringBeforeLast(".")
        val tplDirName = baseName + "_" + stem

        val slots = buildTemplateSlots(bank, info)

        for (slot in slots) {
            currentCoroutineContext().ensureActive()
            val subDir = tplDirName + "/" + slot.folderPath
            var sampleWritten = false
            if (includeAudio) {
                try {
                    val entry = bank.entries.first { it.id == slot.id }
                    val wemBytes = bank.extract(entry)
                    val result = wemToPlayableAudio(context, wemBytes)
                    if (result != null) {
                        val (sampleBytes, ext) = result
                        val sampleName = SAMPLE_PREFIX + ext
                        if (dirUri != null) saveToDir(context, dirUri, sampleName, sampleBytes, subDir)
                        else saveToDownloads(context, sampleName, sampleBytes, subDir)
                        sampleWritten = true
                    } else {
                        // result == null 表示截断预取
                        android.util.Log.w("WwiseTpl", "truncated prefetch sample skipped: " + slot.id)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("WwiseTpl", "sample encode failed for " + slot.id, e)
                }
            }
            // SAF 不能创建空目录：示例音频缺失时写 0 字节占位，保证槽位文件夹存在——
            // 否则用户回读模板时 scanFolderTree 找不到该文件夹，替换被静默跳过
            if (!sampleWritten) {
                if (dirUri != null) saveToDir(context, dirUri, SAMPLE_PREFIX + ".placeholder", ByteArray(0), subDir)
                else saveToDownloads(context, SAMPLE_PREFIX + ".placeholder", ByteArray(0), subDir)
            }
        }

        val readme = buildTemplateReadme(bankName, slots.size, includeAudio).toByteArray(Charsets.UTF_8)
        if (dirUri != null) saveToDir(context, dirUri, "READ_ME.txt", readme, tplDirName)
        else saveToDownloads(context, "READ_ME.txt", readme, tplDirName)

        results.add(TemplateResult(slots.size, tplDirName))
    }
    results
}

private fun buildTemplateReadme(bankName: String, count: Int, includeAudio: Boolean): String {
    val sb = StringBuilder()
    sb.append("=== ").append(L.s(R.string.tpl_readme_title)).append(" ===\n\n")
    sb.append(L.s(R.string.tpl_readme_bank)).append(": ").append(bankName).append('\n')
    sb.append(L.s(R.string.tpl_readme_count)).append(": ").append(count).append('\n')
    sb.append(L.s(R.string.tpl_readme_audio)).append(": ")
        .append(if (includeAudio) L.s(R.string.tpl_readme_yes) else L.s(R.string.tpl_readme_no))
        .append("\n\n")
    sb.append(L.s(R.string.tpl_readme_step1)).append('\n')
    sb.append(L.s(R.string.tpl_readme_step2)).append('\n')
    sb.append(L.s(R.string.tpl_readme_step3)).append('\n')
    sb.append(L.s(R.string.tpl_readme_step4)).append('\n')
    return sb.toString()
}

// ---------- 文件夹打包 ----------

/**
 * 扫描用户提供的替换文件夹，按文件夹名反查事件 -> WEM ID。
 *
 * 文件夹结构约定（与导出模板一致）：
 *   根目录/
 *     Play_Cannon_Fire/       <- 事件名（精确匹配，大小写不敏感）
 *       my_cannon.wav         <- 替换音频（任意命名，只能有一个非 __sample__ 开头的文件）
 *     Play_Engine/
 *       1/
 *         replaced.ogg
 *       2/
 *                             <- 空文件夹 = 跳过该条目
 *     _unassigned/
 *       123456789/            <- 纯数字 = 直接用作 ID
 *         my_sound.flac
 */
suspend fun buildPackPlans(
    context: Context,
    bankSels: List<Pair<Uri, String>>,
    treeUri: Uri,
    info: WwiseConverter.SoundbanksInfo,
    progress: (String) -> Unit
): List<PackPlan> = withContext(Dispatchers.IO) {
    val plans = ArrayList<PackPlan>()

    // 扫描文件夹树：收集 (文件夹相对路径 -> 其下的音频文件列表)
    val folderFiles = scanFolderTree(context, treeUri)

    for ((bankUri, bankName) in bankSels) {
        currentCoroutineContext().ensureActive()
        progress(L.s(R.string.x_scanning))

        val input = context.contentResolver.openInputStream(bankUri)?.use { it.readBytes() }
            ?: throw IllegalStateException(L.s(R.string.e_cant_read_name) + bankName)
        val wasDvpl = DvplCodec.isDvplFile(input)
        val data = if (wasDvpl) DvplCodec.decode(input) else input
        val bank = WwiseConverter.parse(data) ?: throw IllegalStateException(L.s(R.string.e_not_pck))

        val eventToId = buildEventIndex(bank, info)
        val slots = buildTemplateSlots(bank, info)
        val slotByPath = slots.associateBy { it.folderPath.lowercase() }

        val replacements = LinkedHashMap<Long, ByteArray>()
        val errors = ArrayList<String>()
        val unmatched = ArrayList<String>()
        var emptySlots = 0
        var autoConverted = 0

        for ((relPath, audioFiles) in folderFiles) {
            currentCoroutineContext().ensureActive()
            // 忽略示例文件（文件名以 __sample__ 开头）
            val userFiles = audioFiles.filter { !it.second.lowercase().startsWith(SAMPLE_PREFIX) }
            if (userFiles.isEmpty()) { emptySlots++; continue }
            if (userFiles.size > 1) {
                errors.add(L.s(R.string.e_folder_multi_file, relPath,
                    userFiles.joinToString(", ") { it.second }))
                continue
            }

            val targetId = resolveFolder(relPath, slotByPath, eventToId, bank)
            if (targetId == null) {
                // 用户放了音频但文件夹不匹配任何事件/ID：不再静默跳过，
                // 收集起来在预览对话框中提示（多 ID 同名事件需带编号子文件夹等）
                android.util.Log.w("WwisePack", "unrecognized folder: $relPath")
                unmatched.add(relPath)
                continue
            }

            val (fileUri, fileName) = userFiles[0]
            progress(L.s(R.string.x_converting) + " " + fileName)
            val wemBytes = try {
                if (fileName.endsWith(".wem", ignoreCase = true)) {
                    context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                        ?: continue
                } else {
                    autoConverted++
                    encodeAnyToWem(context, fileUri)
                }
            } catch (e: Exception) {
                errors.add(L.s(R.string.e_convert_failed, fileName, e.message ?: e.javaClass.simpleName))
                continue
            }

            replacements[targetId] = wemBytes
        }

        // 全新 ID 重建需要保住的事件根 ID（JSON 事件 Id 在 bank HIRC 内命中的对象）
        val protectedIds = if (bank.hircSize > 0 && info.eventNames.isNotEmpty()) {
            val objIds = WwiseConverter.hircObjectIds(bank)
            HashSet(info.eventNames.keys.filter { objIds.contains(it) })
        } else emptySet()

        plans.add(PackPlan(bankName, bank, wasDvpl, replacements, emptySlots, autoConverted, errors, unmatched, protectedIds))
    }
    plans
}

/**
 * 递归扫描 SAF 树，返回 Map<相对路径, List<Pair<Uri, 文件名>>>
 * 只收集含音频文件的叶子文件夹
 */
private suspend fun scanFolderTree(context: Context, treeUri: Uri): Map<String, List<Pair<Uri, String>>> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyMap()
    val result = LinkedHashMap<String, MutableList<Pair<Uri, String>>>()

    suspend fun walk(dir: DocumentFile, rel: String, depth: Int) {
        if (depth > 10) return
        for (f in dir.listFiles()) {
            // 每个条目检查协程取消：大量文件时用户可随时中断扫描
            currentCoroutineContext().ensureActive()
            val name = f.name ?: continue
            if (f.isDirectory) {
                val childRel = if (rel.isEmpty()) name else rel + "/" + name
                walk(f, childRel, depth + 1)
            } else {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in AUDIO_EXTS && rel.isNotEmpty()) {
                    result.getOrPut(rel) { ArrayList() }.add(Pair(f.uri, name))
                }
            }
        }
    }
    walk(root, "", 0)
    return result
}

/**
 * 构建 事件名(小写) -> ID 反查表（仅单条目事件）
 */
private fun buildEventIndex(bank: WwiseConverter.Bank, info: WwiseConverter.SoundbanksInfo): Map<String, Long> {
    val map = LinkedHashMap<String, Long>()
    val eventCount = HashMap<String, Int>()
    for (e in bank.entries) {
        for (ev in info.events[e.id].orEmpty()) {
            if (ev.isNotBlank()) eventCount[ev] = (eventCount[ev] ?: 0) + 1
        }
    }
    for (e in bank.entries) {
        val events = info.events[e.id].orEmpty()
        val first = events.firstOrNull { it.isNotBlank() } ?: continue
        if ((eventCount[first] ?: 0) == 1) {
            map.putIfAbsent(sanitizeFolderName(first).lowercase(), e.id)
            map.putIfAbsent(WwiseConverter.normalizeForMatch(first), e.id)
        }
    }
    // HIRC 兜底：JSON 查不到的条目用 bank 内置事件结构命名（单媒体事件才进索引）
    val hirc = hircMediaFolders(bank, info)
    if (hirc.isNotEmpty()) {
        val entryIds = HashSet<Long>(bank.entries.size * 2)
        for (e in bank.entries) entryIds.add(e.id)
        val nameCount = HashMap<String, Int>()
        for ((mid, name) in hirc) {
            if (entryIds.contains(mid)) nameCount[name] = (nameCount[name] ?: 0) + 1
        }
        for ((mid, name) in hirc) {
            if (entryIds.contains(mid) && (nameCount[name] ?: 0) == 1) {
                map.putIfAbsent(sanitizeFolderName(name).lowercase(), mid)
                map.putIfAbsent(WwiseConverter.normalizeForMatch(name), mid)
            }
        }
    }
    return map
}

/**
 * 根据文件夹相对路径找到目标 ID：
 * 1) _unassigned/ 前缀 + 纯数字 -> 直接解析 ID
 * 2) 完整路径在 slotByPath 里精确命中
 * 3) 最底层文件夹名（叶子）在 eventToId 里命中
 */
private fun resolveFolder(
    relPath: String,
    slotByPath: Map<String, TemplateSlot>,
    eventToId: Map<String, Long>,
    bank: WwiseConverter.Bank
): Long? {
    val lowerPath = relPath.lowercase().trim('/')

    if (lowerPath.startsWith("_unassigned/")) {
        val idStr = lowerPath.removePrefix("_unassigned/").trim('/')
        val id = idStr.toLongOrNull()
        if (id != null && bank.entries.any { it.id == id }) return id
    }

    slotByPath[lowerPath]?.let { return it.id }

    val leaf = lowerPath.substringAfterLast('/')
    eventToId[leaf]?.let { return it }
    eventToId[WwiseConverter.normalizeForMatch(leaf)]?.let { return it }

    return null
}

// ---------- 打包执行 ----------

/**
 * 执行打包，输出 {stem}_repacked.pck/.bnk[.dvpl]
 */
suspend fun executePackPlans(
    context: Context,
    plans: List<PackPlan>,
    dirUri: Uri?,
    groupByType: Boolean,
    subDirName: String,
    rebuildFreshIds: Boolean = false
): List<Triple<String, Boolean, String>> = withContext(Dispatchers.IO) {
    plans.map { plan ->
        try {
            currentCoroutineContext().ensureActive()
            if (plan.errors.isNotEmpty()) {
                return@map Triple(plan.bankName, false,
                    L.s(R.string.e_pack_has_errors) + plan.errors.first())
            }
            if (plan.replacements.isEmpty()) {
                return@map Triple(plan.bankName, false, L.s(R.string.x_none_matched))
            }

            val repacked = if (rebuildFreshIds && plan.bank.hircSize > 0) {
                // 全新 ID 重建：事件根/总线保留，替换媒体与内部对象全换新 ID
                WwiseConverter.buildBank(plan.bank, plan.replacements, plan.protectedIds)
            } else {
                WwiseConverter.repack(plan.bank, plan.replacements)
            }
            val outData = if (plan.wasDvpl)
                DvplCodec.encode(repacked.bytes, DvplCodec.COMPRESSION_LZ4_HC) else repacked.bytes

            var baseName = plan.bankName
            if (baseName.endsWith(".dvpl", ignoreCase = true)) baseName = baseName.dropLast(5)
            val isPck = baseName.endsWith(".pck", ignoreCase = true)
            val stem = baseName.dropLast(4)
            val outName = stem + "_repacked" + (if (isPck) ".pck" else ".bnk") +
                (if (plan.wasDvpl) ".dvpl" else "")
            val subDir = if (groupByType) subDirName else null
            if (dirUri != null) saveToDir(context, dirUri, outName, outData, subDir)
            else saveToDownloads(context, outName, outData, subDir)

            val notes = ArrayList<String>()
            notes.add(L.s(R.string.x_replaced_prefix) + plan.replacements.size +
                L.s(R.string.x_replaced_suffix) + outName)
            if (plan.emptySlots > 0)
                notes.add(plan.emptySlots.toString() + L.s(R.string.x_empty_slots_skipped))
            if (plan.autoConverted > 0)
                notes.add(plan.autoConverted.toString() + L.s(R.string.x_auto_converted))
            if (repacked.truncatedReplacements > 0)
                notes.add(L.s(R.string.x_of_which) + repacked.truncatedReplacements +
                    L.s(R.string.x_truncated_prefix))
            Triple(plan.bankName, true, notes.joinToString("；"))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("WwisePack", "FAIL: " + plan.bankName, e)
            Triple(plan.bankName, false, e.message ?: e.javaClass.simpleName)
        }
    }
}
