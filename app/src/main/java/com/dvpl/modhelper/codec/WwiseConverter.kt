package com.dvpl.modhelper.codec

import org.json.JSONObject
import com.dvpl.modhelper.L
import com.dvpl.modhelper.R

/**
 * Wwise PCK/BNK 解包与打包（纯字节级，无音频编解码）
 *
 * 格式实测（对全部 36 个 .pck + 54 个 .bnk 游戏文件验证，2024 逆向共识一致）：
 *  - PCK（AKPK 头）：
 *      0x00 "AKPK"
 *      0x04 u32 索引区大小（DATA 起始 = 8 + 此值；条目数不变则重打包后不变）
 *      0x08 u32 版本 = 1
 *      0x0C..0x33 语言表（20 字节，重打包原样保留）
 *      0x34 u32 文件数 N
 *      0x38 起 N × 20 字节条目：(u32 wemId, u32 langId, u32 size, u32 offset绝对, u32 0)
 *      0x38+20N 处 4 字节 0（pad）
 *      之后 DATA（wem 数据，offset 为文件内绝对偏移）
 *  - BNK：顺序分节 (char[4] magic, u32 size, payload)
 *      BKHD（头）、DIDX（N × 12 字节条目：(u32 wemId, u32 offset相对DATA, u32 size)）、
 *      DATA（wem 数据）、HIRC（事件结构，重打包逐字节原样保留）
 *
 * 重打包原则：只改 DIDX/PCK 条目的 size/offset 与 DATA 内容，wemId/langId/其余分节
 * 一律原样——HIRC 事件按 wemId 引用媒体，id 不变即可正常索引。
 */
object WwiseConverter {

    /** PCK/BNK 中的一条媒体（wem）记录 */
    class WemEntry(val id: Long, val langId: Long, val size: Int, val offset: Int) {
        /** 提取到 wem 字节 */
        fun extractFrom(bank: ByteArray, dataStart: Int): ByteArray {
            require(offset >= 0 && size >= 0 && offset + size <= bank.size) {
                L.s(R.string.tpl_wem_oob, id, offset, size)
            }
            return bank.copyOfRange(offset, offset + size)
        }
    }

    /** 解析后的 bank（已解 DVPL 包裹的原始字节） */
    class Bank(
        val isPck: Boolean,
        /** wem 条目（PCK 的 offset 已折算为绝对偏移） */
        val entries: List<WemEntry>,
        /** DATA 段起始（PCK：绝对；BNK：DATA payload 起始，条目 offset 为相对值） */
        val dataStart: Int,
        /** 原始 bank 字节（重打包时复用） */
        val original: ByteArray
    ) {
        fun extract(entry: WemEntry): ByteArray = entry.extractFrom(original, dataStart)
    }

    /** 解析 pck/bnk（pvr/dvpl 等其他格式返回 null） */
    fun parse(data: ByteArray): Bank? {
        if (data.size < 0x40) return null
        val magic = String(data, 0, 4, Charsets.US_ASCII)
        return when (magic) {
            "AKPK" -> parsePck(data)
            "BKHD" -> parseBnk(data)
            else -> null
        }
    }

    // ---------- PCK ----------

    private fun parsePck(data: ByteArray): Bank? {
        // AKPK 有两种版本，由 0x0C 处的字段区分：
        //   0x0C = 0x14 (20)：旧版，count@0x34，entry table@0x38
        //   0x0C = 0x10 (16)：新版，count@0x30，entry table@0x34
        // entry 结构两者相同（20B）：id, langId, size, offAbs(绝对), unk
        val unkC = readU32(data, 0x0C)
        val (countOff, tableStart) = when (unkC) {
            0x14 -> Pair(0x34, 0x38)
            0x10 -> Pair(0x30, 0x34)
            else -> return null // 未知格式
        }
        val count = readU32(data, countOff)
        if (count > 100_000) return null
        val tableEnd = tableStart + 20 * count
        if (tableEnd > data.size) return null
        val entries = ArrayList<WemEntry>(count)
        for (i in 0 until count) {
            val o = tableStart + 20 * i
            val id = readU32(data, o)
            val langId = readU32(data, o + 4)
            val size = readU32(data, o + 8)
            val offAbs = readU32(data, o + 12)
            if (offAbs + size > data.size) return null // 坏表
            entries.add(WemEntry(id.toLong(), langId.toLong(), size, offAbs))
        }
        // dataStart 仅用于 repack 定位数据区起点；从第一条 entry 的最小偏移推算
        val dataStart = if (entries.isEmpty()) tableEnd else entries.minOf { it.offset }
        return Bank(true, entries, dataStart, data)
    }

    // ---------- BNK ----------

    private class Section(val magic: String, val payloadStart: Int, val payloadSize: Int)

    private fun parseBnk(data: ByteArray): Bank? {
        val sections = ArrayList<Section>()
        var pos = 0
        while (pos + 8 <= data.size) {
            val magic = String(data, pos, 4, Charsets.US_ASCII)
            val sz = readU32(data, pos + 4)
            if (sz < 0 || pos + 8 + sz > data.size) return null
            sections.add(Section(magic, pos + 8, sz))
            pos += 8 + sz
        }
        if (pos != data.size) return null // 尾部有残缺，拒绝解析
        val didx = sections.firstOrNull { it.magic == "DIDX" }
        val dataSec = sections.firstOrNull { it.magic == "DATA" }
        if (didx == null || dataSec == null) {
            // 纯事件库（Init/utility_events 等无媒体）：返回空条目表
            return Bank(false, emptyList(), -1, data)
        }
        if (didx.payloadSize % 12 != 0) return null
        val n = didx.payloadSize / 12
        val entries = ArrayList<WemEntry>(n)
        for (i in 0 until n) {
            val o = didx.payloadStart + 12 * i
            val id = readU32(data, o)
            val off = readU32(data, o + 4)
            val size = readU32(data, o + 8)
            entries.add(WemEntry(id.toLong(), 1L, size, dataSec.payloadStart + off))
        }
        return Bank(false, entries, dataSec.payloadStart, data)
    }

    // ---------- 重打包 ----------

    /** 重打包结果：新 bank 字节 + 被截断为预取前缀的替换条目数 */
    class RepackResult(val bytes: ByteArray, val truncatedReplacements: Int)

    /**
     * 重打包：replacements 按 wemId 替换媒体字节，未命中的条目原样搬运。
     * 返回新的 bank 字节（DATA 按 DIDX/条目顺序连续重排，索引重算）。
     *
     * 流式预取截断：bnk 里原条目若是截断预取（RIFF 声明大小超出实际存储），
     * 替换 wem 超出原存储字节数的部分截掉（只保留前 N 字节前缀，N=原存储大小），
     * 完整音频由 modder 用同一批 wem 重打同名 .pck 提供——bnk 前缀与 pck 完整
     * 文件取自同一个新 wem 的头部，引擎拼接后即为完整新音频。
     */
    fun repack(bank: Bank, replacements: Map<Long, ByteArray>): RepackResult {
        if (bank.entries.isEmpty()) throw IllegalStateException(L.s(R.string.e_bank_no_media))
        // 先统一取媒体字节（小库直接持有；大库逐条从 original 拷贝）
        val media = ArrayList<ByteArray>(bank.entries.size)
        var truncated = 0
        for (e in bank.entries) {
            val rep = replacements[e.id]
            if (rep == null) {
                media.add(bank.extract(e))
                continue
            }
            val orig = bank.extract(e)
            if (isTruncatedWem(orig) && rep.size > e.size) {
                media.add(rep.copyOf(e.size))
                truncated++
            } else {
                media.add(rep)
            }
        }
        val bytes = if (bank.isPck) repackPck(bank, media) else repackBnk(bank, media)
        return RepackResult(bytes, truncated)
    }

    private fun repackPck(bank: Bank, media: List<ByteArray>): ByteArray {
        val count = bank.entries.size
        val dataStart = 0x38 + 20 * count + 4
        var total = dataStart.toLong()
        for (m in media) total += m.size
        val out = java.io.ByteArrayOutputStream(total.toInt())
        // 头 0x00..0x38 原样（含文件数与索引区大小——条目数不变，二者都不变）
        out.write(bank.original, 0, 0x38)
        var cum = dataStart // PCK offset 为绝对偏移，从 DATA 起始累计
        val bb = java.nio.ByteBuffer.allocate(20 * count).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in bank.entries.indices) {
            val e = bank.entries[i]
            bb.putInt(e.id.toInt())
            bb.putInt(e.langId.toInt())
            bb.putInt(media[i].size)
            bb.putInt(cum)
            bb.putInt(0)
            cum += media[i].size
        }
        out.write(bb.array())
        val pad = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.write(pad.array())
        for (m in media) out.write(m)
        return out.toByteArray()
    }

    private fun repackBnk(bank: Bank, media: List<ByteArray>): ByteArray {
        // 重算 DIDX：offset 相对 DATA payload，按条目顺序连续；
        // 媒体条目按 16 字节对齐填充（与 Wwise 原始写出一致，最后一条不留尾填充）——
        // 这样未替换任何 wem 时重打结果与原库逐字节相同，可直接用 sha256 验证完整性。
        val padOf = { i: Int ->
            if (i < media.size - 1) (16 - media[i].size % 16) % 16 else 0
        }
        var total = 0L
        for (i in media.indices) total += media[i].size + padOf(i)
        val dataSize = total.toInt()
        // 遍历原始分节，按原顺序重写：DIDX/DATA 重算，其余逐字节复制
        val out = java.io.ByteArrayOutputStream(bank.original.size + 1024)
        var pos = 0
        val didxTable = java.nio.ByteBuffer.allocate(12 * bank.entries.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var cum = 0
        for (i in bank.entries.indices) {
            val e = bank.entries[i]
            didxTable.putInt(e.id.toInt())
            didxTable.putInt(cum)
            didxTable.putInt(media[i].size)
            cum += media[i].size + padOf(i)
        }
        val zeroPad = ByteArray(16)
        while (pos + 8 <= bank.original.size) {
            val magic = String(bank.original, pos, 4, Charsets.US_ASCII)
            val sz = readU32(bank.original, pos + 4)
            val hdr = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            when (magic) {
                "DIDX" -> {
                    hdr.put("DIDX".toByteArray(Charsets.US_ASCII)); hdr.putInt(didxTable.capacity())
                    out.write(hdr.array()); out.write(didxTable.array())
                }
                "DATA" -> {
                    hdr.put("DATA".toByteArray(Charsets.US_ASCII)); hdr.putInt(dataSize)
                    out.write(hdr.array())
                    for (i in media.indices) {
                        out.write(media[i])
                        val pad = padOf(i)
                        if (pad > 0) out.write(zeroPad, 0, pad)
                    }
                }
                else -> {
                    hdr.put(magic.toByteArray(Charsets.US_ASCII)); hdr.putInt(sz)
                    out.write(hdr.array())
                    out.write(bank.original, pos + 8, sz)
                }
            }
            pos += 8 + sz
        }
        return out.toByteArray()
    }

    // ---------- SoundbanksInfo.json 命名映射 ----------

    /** SoundbanksInfo 解析结果 */
    class SoundbanksInfo(
        /** wemId → 原始短名（ShortName 去路径去扩展，用于命名显示与跨语言匹配） */
        val names: Map<Long, String>,
        /** wemId → 原始目录（ShortName 目录部分，归一化为 a/b 形式；顶层为空串） */
        val dirs: Map<Long, String>,
        /** wemId → 引用该文件的 Wwise 事件名列表（回答「什么时候播放」） */
        val events: Map<Long, List<String>>
    )

    private val emptyInfo = SoundbanksInfo(emptyMap(), emptyMap(), emptyMap())

    /**
     * 解析 SoundbanksInfo.json。递归遍历整棵 JSON 树：
     *  - 带 Id+ShortName/Path 的对象 → 文件命名/目录（覆盖 StreamedFiles /
     *    IncludedMemoryFiles 等所有嵌套形态，DialogueEvents 无 ShortName 自动跳过）；
     *  - 带 Name+ReferencedStreamedFiles/IncludedMemoryFiles 的对象 → Wwise 事件，
     *    把事件名挂到其引用的所有文件 id 上。
     */
    fun parseSoundbanksInfo(json: ByteArray): SoundbanksInfo {
        return try {
            val names = HashMap<Long, String>()
            val dirs = HashMap<Long, String>()
            val events = HashMap<Long, MutableList<String>>()
            val root = JSONObject(String(json, Charsets.UTF_8))
            walkJson(root, names, dirs, events)
            SoundbanksInfo(names, dirs, events)
        } catch (e: Exception) {
            emptyInfo
        }
    }

    private fun walkJson(
        node: Any?,
        names: HashMap<Long, String>,
        dirs: HashMap<Long, String>,
        events: HashMap<Long, MutableList<String>>
    ) {
        when (node) {
            is JSONObject -> {
                val id = node.opt("Id")
                if (id is String || id is Int || id is Long) {
                    val idLong = id.toString().toLongOrNull()
                    if (idLong != null) {
                        val short = node.optString("ShortName").ifEmpty { node.optString("Path") }
                        if (short.isNotEmpty()) {
                            val name = sanitizeBaseName(short)
                            if (name.isNotEmpty() && !names.containsKey(idLong)) {
                                names[idLong] = name
                                dirs[idLong] = sanitizeDir(short)
                            }
                        }
                    }
                }
                // Wwise 事件：Name + 引用文件列表
                val evName = node.optString("Name")
                if (evName.isNotEmpty() &&
                    (node.has("ReferencedStreamedFiles") || node.has("IncludedMemoryFiles"))
                ) {
                    for (key in arrayOf("ReferencedStreamedFiles", "IncludedMemoryFiles")) {
                        val arr = node.optJSONArray(key) ?: continue
                        for (i in 0 until arr.length()) {
                            val fo = arr.optJSONObject(i) ?: continue
                            val fid = fo.optString("Id").toLongOrNull() ?: continue
                            events.getOrPut(fid) { ArrayList() }.apply {
                                if (!contains(evName)) add(evName)
                            }
                        }
                    }
                }
                for (key in node.keys()) walkJson(node.get(key), names, dirs, events)
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) walkJson(node.get(i), names, dirs, events)
            }
        }
    }

    /** 路径基名 → 合法文件名（去目录、去扩展、替换非法字符） */
    private fun sanitizeBaseName(path: String): String {
        val base = path.substringAfterLast('\\').substringAfterLast('/')
            .substringBeforeLast('.')
        return base.replace(Regex("[\\/:*?\"<>|\\s]"), "_").take(80)
    }

    /** 路径 → 归一化目录（a\\b\\c.wav → a/b；顶层返回空串；每段做合法化） */
    private fun sanitizeDir(path: String): String {
        val dir = path.substringBeforeLast('\\').substringBeforeLast('/')
        if (dir == path || dir.isEmpty()) return ""
        return dir.split('\\', '/')
            .filter { it.isNotBlank() }
            .joinToString("/") { seg -> seg.replace(Regex("[\\/:*?\"<>|]"), "_").take(60) }
    }

    /** 检测 wem 是否为 PCM（fmt codec 0x0001，可直接当 .wav 用） */
    fun isPcmWem(data: ByteArray): Boolean {
        if (data.size < 32) return false
        if (!(data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte())) return false
        var pos = 12
        while (pos + 8 <= data.size) {
            val id = String(data, pos, 4, Charsets.US_ASCII)
            val sz = readU32(data, pos + 4)
            if (id == "fmt ") {
                if (pos + 8 + 2 > data.size) return false
                val codec = (data[pos + 8].toInt() and 0xFF) or
                    ((data[pos + 9].toInt() and 0xFF) shl 8)
                return codec == 0x0001
            }
            pos += 8 + sz
        }
        return false
    }

    // ---------- PtADPCM（Wwise 2019.1+ 语音编码，fmt codec 0x8311，Platinum 自定义 ADPCM） ----------

    /** PtADPCM 查找表：t[(index*16+nibble)*2]=步长增量，t[+1]=新索引（低 nibble 在前） */
    private val ptAdpcmTable = intArrayOf(
        -14, 2, -10, 2, -7, 1, -5, 1,
        -3, 0, -2, 0, -1, 0, 0, 0,
        0, 0, 1, 0, 2, 0, 3, 0,
        5, 1, 7, 1, 10, 2, 14, 2,
        -28, 3, -20, 3, -14, 2, -10, 2,
        -7, 1, -5, 1, -3, 1, -1, 0,
        1, 0, 3, 1, 5, 1, 7, 1,
        10, 2, 14, 2, 20, 3, 28, 3,
        -56, 4, -40, 4, -28, 3, -20, 3,
        -14, 2, -10, 2, -6, 2, -2, 1,
        2, 1, 6, 2, 10, 2, 14, 2,
        20, 3, 28, 3, 40, 4, 56, 4,
        -112, 5, -80, 5, -56, 4, -40, 4,
        -28, 3, -20, 3, -12, 3, -4, 2,
        4, 2, 12, 3, 20, 3, 28, 3,
        40, 4, 56, 4, 80, 5, 112, 5,
        -224, 6, -160, 6, -112, 5, -80, 5,
        -56, 4, -40, 4, -24, 4, -8, 3,
        8, 3, 24, 4, 40, 4, 56, 4,
        80, 5, 112, 5, 160, 6, 224, 6,
        -448, 7, -320, 7, -224, 6, -160, 6,
        -112, 5, -80, 5, -48, 5, -16, 4,
        16, 4, 48, 5, 80, 5, 112, 5,
        160, 6, 224, 6, 320, 7, 448, 7,
        -896, 8, -640, 8, -448, 7, -320, 7,
        -224, 6, -160, 6, -96, 6, -32, 5,
        32, 5, 96, 6, 160, 6, 224, 6,
        320, 7, 448, 7, 640, 8, 896, 8,
        -1792, 9, -1280, 9, -896, 8, -640, 8,
        -448, 7, -320, 7, -192, 7, -64, 6,
        64, 6, 192, 7, 320, 7, 448, 7,
        640, 8, 896, 8, 1280, 9, 1792, 9,
        -3584, 10, -2560, 10, -1792, 9, -1280, 9,
        -896, 8, -640, 8, -384, 8, -128, 7,
        128, 7, 384, 8, 640, 8, 896, 8,
        1280, 9, 1792, 9, 2560, 10, 3584, 10,
        -7168, 11, -5120, 11, -3584, 10, -2560, 10,
        -1792, 9, -1280, 9, -768, 9, -256, 8,
        256, 8, 768, 9, 1280, 9, 1792, 9,
        2560, 10, 3584, 10, 5120, 11, 7168, 11,
        -14336, 11, -10240, 11, -7168, 11, -5120, 11,
        -3584, 10, -2560, 10, -1536, 10, -512, 9,
        512, 9, 1536, 10, 2560, 10, 3584, 10,
        5120, 11, 7168, 11, 10240, 11, 14336, 11,
        -28672, 11, -20480, 11, -14336, 11, -10240, 11,
        -7168, 11, -5120, 11, -3072, 11, -1024, 10,
        1024, 10, 3072, 11, 5120, 11, 7168, 11,
        10240, 11, 14336, 11, 20480, 11, 28672, 11,
        // index 12：全零（越界保护）
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    )

    /** 检测 wem 是否为 PtADPCM 编码（fmt codec 0x8311，Wwise 2019.1+ 语音常用） */
    fun isPtAdpcmWem(data: ByteArray): Boolean {
        if (data.size < 32) return false
        if (!(data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte())) return false
        var pos = 12
        while (pos + 8 <= data.size) {
            val id = String(data, pos, 4, Charsets.US_ASCII)
            val sz = readU32(data, pos + 4)
            if (id == "fmt ") {
                if (pos + 8 + 2 > data.size) return false
                val codec = (data[pos + 8].toInt() and 0xFF) or
                    ((data[pos + 9].toInt() and 0xFF) shl 8)
                return codec == 0x8311
            }
            pos += 8 + sz
        }
        return false
    }

    /**
     * PtADPCM wem 解码为 16bit PCM WAV，支持单/多声道。
     *
     * 帧布局（external interleave，每声道独立）：
     *   data = [ch0_frame0][ch1_frame0][ch0_frame1][ch1_frame1]...
     *   每帧 frameSize = blockAlign / channels 字节：
     *     +0 s16 hist2, +2 s16 hist1, +4 u8 index, +5.. nibbles（低 nibble 先）
     *   每帧输出 samplesPerFrame = 2 + (frameSize-5)*2 个样本
     *
     * 多声道输出为交织 PCM：L0 R0 L1 R1 ...
     */
    fun decodePtAdpcmToWav(wem: ByteArray): ByteArray {
        var channels = 1
        var sampleRate = 16000
        var blockAlign = 36
        var dataOff = -1
        var dataSize = 0
        var pos = 12
        while (pos + 8 <= wem.size) {
            val id = String(wem, pos, 4, Charsets.US_ASCII)
            val sz = readU32(wem, pos + 4)
            if (id == "fmt " && pos + 8 + 16 <= wem.size) {
                channels = (wem[pos + 10].toInt() and 0xFF) or ((wem[pos + 11].toInt() and 0xFF) shl 8)
                sampleRate = readU32(wem, pos + 12)
                blockAlign = (wem[pos + 20].toInt() and 0xFF) or ((wem[pos + 21].toInt() and 0xFF) shl 8)
            } else if (id == "data") {
                dataOff = pos + 8
                dataSize = sz
            }
            pos += 8 + sz
        }
        if (dataOff < 0 || dataSize <= 0) throw IllegalArgumentException(L.s(R.string.e_wem_no_data))
        if (channels < 1) throw IllegalArgumentException(L.s(R.string.tpl_pt_channels, channels))
        // frameSize：每个声道每帧占用的字节数
        val frameSize = blockAlign / channels
        if (frameSize < 6) throw IllegalArgumentException(L.s(R.string.tpl_pt_blockalign, blockAlign, channels))
        val samplesPerFrame = 2 + (frameSize - 5) * 2
        // 总帧组数（每"组"包含 channels 个帧，对应同一时间段的所有声道）
        val frameGroups = dataSize / blockAlign
        val totalSamples = frameGroups * samplesPerFrame // 每声道样本数
        val t = ptAdpcmTable

        // 每个声道独立解码为短整型数组
        val chBufs = Array(channels) { ShortArray(totalSamples) }
        for (g in 0 until frameGroups) {
            for (ch in 0 until channels) {
                val p = dataOff + g * blockAlign + ch * frameSize
                var hist2 = ((wem[p].toInt() and 0xFF) or (wem[p + 1].toInt() shl 8)).toShort()
                var hist1 = ((wem[p + 2].toInt() and 0xFF) or (wem[p + 3].toInt() shl 8)).toShort()
                var index = wem[p + 4].toInt() and 0xFF
                if (index > 12) index = 12
                val base = g * samplesPerFrame
                chBufs[ch][base]     = hist2
                chBufs[ch][base + 1] = hist1
                val nibbleCount = (frameSize - 5) * 2
                for (i in 0 until nibbleCount) {
                    val b = wem[p + 5 + (i shr 1)].toInt() and 0xFF
                    val n = if (i and 1 == 0) b and 0xF else (b shr 4) and 0xF
                    val ti = (index * 16 + n) * 2
                    val step = t[ti]
                    index = t[ti + 1]
                    var s = step + 2 * hist1.toInt() - hist2.toInt()
                    if (s > 32767) s = 32767 else if (s < -32768) s = -32768
                    chBufs[ch][base + 2 + i] = s.toShort()
                    hist2 = hist1
                    hist1 = s.toShort()
                }
            }
        }

        // 交织为多声道 PCM（L0 R0 L1 R1 ...）
        val pcm = ByteArray(totalSamples * channels * 2)
        var op = 0
        for (s in 0 until totalSamples) {
            for (ch in 0 until channels) {
                val v = chBufs[ch][s].toInt()
                pcm[op++] = (v and 0xFF).toByte()
                pcm[op++] = ((v shr 8) and 0xFF).toByte()
            }
        }

        val wav = ByteArray(44 + pcm.size)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(wav, 0)
        wav.writeU32(36 + pcm.size, 4)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(wav, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(wav, 12)
        wav.writeU32(16, 16)
        wav.writeU16(1, 20)          // PCM
        wav.writeU16(channels, 22)
        wav.writeU32(sampleRate, 24)
        wav.writeU32(sampleRate * 2 * channels, 28)
        wav.writeU16(2 * channels, 32)
        wav.writeU16(16, 34)
        "data".toByteArray(Charsets.US_ASCII).copyInto(wav, 36)
        wav.writeU32(pcm.size, 40)
        pcm.copyInto(wav, 44)
        return wav
    }


    // ---------- IMA ADPCM（Wwise codec 0x0002/0x0069，MS-IMA 块交错格式） ----------

    /** 标准 IMA step 表（89 项） */
    private val imaStepTable = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14,
        16, 17, 19, 21, 23, 25, 28, 31,
        34, 37, 41, 45, 50, 55, 60, 66,
        73, 80, 88, 97, 107, 118, 130, 143,
        157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658,
        724, 796, 876, 963, 1060, 1166, 1282, 1411,
        1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
        3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
        7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )
    private val imaIndexTable = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

    /** 检测 wem 是否为 IMA ADPCM（fmt codec 0x0002 或 0x0069） */
    fun isImaAdpcmWem(data: ByteArray): Boolean {
        if (data.size < 32) return false
        if (!(data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte())) return false
        var pos = 12
        while (pos + 8 <= data.size) {
            val id = String(data, pos, 4, Charsets.US_ASCII)
            val sz = readU32(data, pos + 4)
            if (id == "fmt ") {
                if (pos + 8 + 2 > data.size) return false
                val codec = (data[pos + 8].toInt() and 0xFF) or ((data[pos + 9].toInt() and 0xFF) shl 8)
                return codec == 0x0002 || codec == 0x0069
            }
            pos += 8 + sz
        }
        return false
    }

    /**
     * MS-IMA ADPCM wem 解码为 16bit PCM WAV。
     *
     * Wwise 使用 MS-IMA 块交错（internal interleave）：
     *   帧大小 = blockAlign；每帧开头 4×channels 字节为各声道头部
     *   （s16 hist, u8 stepIndex, u8 reserved），之后数据按 4 字节/声道交替排列。
     *   每声道每帧样本数 = 1 + (blockAlign/channels - 4) * 2。
     */
    fun decodeImaAdpcmToWav(wem: ByteArray): ByteArray {
        var channels = 1
        var sampleRate = 22050
        var blockAlign = 0
        var dataOff = -1
        var dataSize = 0
        var pos = 12
        while (pos + 8 <= wem.size) {
            val id = String(wem, pos, 4, Charsets.US_ASCII)
            val sz = readU32(wem, pos + 4)
            if (id == "fmt " && pos + 8 + 20 <= wem.size) {
                channels   = (wem[pos+10].toInt() and 0xFF) or ((wem[pos+11].toInt() and 0xFF) shl 8)
                sampleRate = readU32(wem, pos + 12)
                blockAlign = (wem[pos+20].toInt() and 0xFF) or ((wem[pos+21].toInt() and 0xFF) shl 8)
            } else if (id == "data") {
                dataOff  = pos + 8
                dataSize = sz
            }
            pos += 8 + sz
        }
        if (dataOff < 0 || dataSize <= 0) throw IllegalArgumentException(L.s(R.string.e_wem_no_data))
        if (channels < 1 || channels > 8) throw IllegalArgumentException(L.s(R.string.tpl_ima_channels, channels))
        if (blockAlign < channels * 4 + 2) throw IllegalArgumentException(L.s(R.string.tpl_ima_blockalign, blockAlign))

        val frameSize   = blockAlign                           // 每帧总字节
        val chFrameSize = blockAlign / channels                // 每声道每帧字节
        val samplesPerFrame = 1 + (chFrameSize - 4) * 2       // 每声道每帧样本数

        val frameCount  = dataSize / frameSize
        val totalSamples = frameCount * samplesPerFrame
        val pcm = ByteArray(totalSamples * channels * 2)
        var outOff = 0

        val hist  = IntArray(channels)
        val index = IntArray(channels)

        for (f in 0 until frameCount) {
            val frameBase = dataOff + f * frameSize
            // 读各声道帧头
            for (ch in 0 until channels) {
                val h = frameBase + ch * 4
                hist[ch]  = (wem[h].toInt() and 0xFF) or (wem[h+1].toInt() shl 8)  // s16 LE
                if (hist[ch] > 32767) hist[ch] -= 65536
                index[ch] = wem[h+2].toInt() and 0xFF
                if (index[ch] > 88) index[ch] = 88
            }
            // 写各声道第一个样本（帧头样本）
            for (ch in 0 until channels) {
                val v = hist[ch].coerceIn(-32768, 32767)
                val outIdx = outOff + ch * 2
                pcm[outIdx]   = (v and 0xFF).toByte()
                pcm[outIdx+1] = ((v shr 8) and 0xFF).toByte()
            }
            outOff += channels * 2

            // 解码剩余样本：数据区按 4 字节/声道交替
            // dataStart 在帧头之后
            val nibbleStart  = frameBase + channels * 4
            // 每声道每组 4 字节（8 nibbles），各声道交替
            val groupsPerCh  = (chFrameSize - 4) / 4           // 每声道 4 字节组数
            for (g in 0 until groupsPerCh) {
                for (ch in 0 until channels) {
                    val byteBase = nibbleStart + g * channels * 4 + ch * 4
                    for (bIdx in 0 until 4) {
                        val byte = wem[byteBase + bIdx].toInt() and 0xFF
                        for (nib in 0 until 2) {
                            val n = if (nib == 0) byte and 0xF else (byte shr 4) and 0xF
                            val step = imaStepTable[index[ch]]
                            var diff = step shr 3
                            if (n and 4 != 0) diff += step
                            if (n and 2 != 0) diff += step shr 1
                            if (n and 1 != 0) diff += step shr 2
                            if (n and 8 != 0) diff = -diff
                            hist[ch] = (hist[ch] + diff).coerceIn(-32768, 32767)
                            index[ch] = (index[ch] + imaIndexTable[n]).coerceIn(0, 88)
                            val v = hist[ch]
                            val outIdx = outOff + ch * 2
                            pcm[outIdx]   = (v and 0xFF).toByte()
                            pcm[outIdx+1] = ((v shr 8) and 0xFF).toByte()
                        }
                        outOff += channels * 2
                    }
                }
            }
        }

        val usedPcm = pcm.copyOf(outOff)
        val wav = ByteArray(44 + usedPcm.size)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(wav, 0)
        wav.writeU32(36 + usedPcm.size, 4)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(wav, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(wav, 12)
        wav.writeU32(16, 16)
        wav.writeU16(1, 20)           // PCM
        wav.writeU16(channels, 22)
        wav.writeU32(sampleRate, 24)
        wav.writeU32(sampleRate * 2 * channels, 28)
        wav.writeU16(2 * channels, 32)
        wav.writeU16(16, 34)
        "data".toByteArray(Charsets.US_ASCII).copyInto(wav, 36)
        wav.writeU32(usedPcm.size, 40)
        usedPcm.copyInto(wav, 44)
        return wav
    }

    // ---------- Wwise Opus（codec 0x3040/0x3041，Android MediaCodec 解码） ----------

    /** 检测 wem 是否为 Wwise Opus（fmt codec 0x3040 或 0x3041） */
    fun isOpusWem(data: ByteArray): Boolean {
        if (data.size < 32) return false
        if (!(data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte())) return false
        var pos = 12
        while (pos + 8 <= data.size) {
            val id = String(data, pos, 4, Charsets.US_ASCII)
            val sz = readU32(data, pos + 4)
            if (id == "fmt ") {
                if (pos + 8 + 2 > data.size) return false
                val codec = (data[pos + 8].toInt() and 0xFF) or ((data[pos + 9].toInt() and 0xFF) shl 8)
                return codec == 0x3040 || codec == 0x3041
            }
            pos += 8 + sz
        }
        return false
    }

    /**
     * Wwise Opus wem 解码为 16bit PCM WAV，通过 Android MediaCodec。
     *
     * Wwise Opus 帧布局（0x3040 和 0x3041 相同）：
     *   seek 块（可选）跳过；data 块中每帧：u16 帧长 + Opus packet。
     * 通过 MediaCodec "audio/opus" 解码，需提供 OpusHead 初始化帧（19 字节）。
     */
    @Suppress("deprecation")
    fun decodeOpusToWav(wem: ByteArray, context: android.content.Context): ByteArray {
        // 1. 解析 fmt / seek / data 块
        var channels   = 2
        var sampleRate = 48000
        var dataOff    = -1
        var dataSize   = 0
        var isWwOpus   = false   // 0x3041 = WwOpus，0x3040 = 标准 Opus 帧
        var pos        = 12
        while (pos + 8 <= wem.size) {
            val id = String(wem, pos, 4, Charsets.US_ASCII)
            val sz = readU32(wem, pos + 4)
            if (id == "fmt " && pos + 8 + 12 <= wem.size) {
                val codec = (wem[pos+8].toInt() and 0xFF) or ((wem[pos+9].toInt() and 0xFF) shl 8)
                isWwOpus = (codec == 0x3041)
                channels   = (wem[pos+10].toInt() and 0xFF) or ((wem[pos+11].toInt() and 0xFF) shl 8)
                sampleRate = readU32(wem, pos + 12)
            } else if (id == "data") {
                dataOff  = pos + 8
                dataSize = sz
            }
            pos += 8 + sz
        }
        if (dataOff < 0 || dataSize <= 0) throw IllegalArgumentException(L.s(R.string.e_wem_no_data))
        if (channels < 1 || channels > 8) throw IllegalArgumentException(L.s(R.string.tpl_opus_channels, channels))

        // 2. 收集 Opus packets（每帧前缀 u16 长度）
        val packets = ArrayList<ByteArray>()
        var p = dataOff
        val dataEnd = dataOff + dataSize
        while (p + 2 <= dataEnd) {
            val pktLen = (wem[p].toInt() and 0xFF) or ((wem[p+1].toInt() and 0xFF) shl 8)
            p += 2
            if (pktLen <= 0 || p + pktLen > dataEnd) break
            packets.add(wem.copyOfRange(p, p + pktLen))
            p += pktLen
        }
        if (packets.isEmpty()) throw IllegalArgumentException(L.s(R.string.e_opus_no_frames))

        // 3. 构造 OpusHead（19 字节）供 MediaCodec CSD
        val opusHead = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(opusHead, 0)
        opusHead[8]  = 1                        // version
        opusHead[9]  = channels.toByte()
        opusHead[10] = 0; opusHead[11] = 0      // pre-skip LE u16
        opusHead[12] = (sampleRate and 0xFF).toByte()
        opusHead[13] = ((sampleRate shr 8) and 0xFF).toByte()
        opusHead[14] = ((sampleRate shr 16) and 0xFF).toByte()
        opusHead[15] = ((sampleRate shr 24) and 0xFF).toByte()
        opusHead[16] = 0; opusHead[17] = 0      // output gain LE s16
        opusHead[18] = 0                         // channel mapping family

        val csd0 = java.nio.ByteBuffer.wrap(opusHead)
        // CSD-1: pre-roll = 80ms @ 48kHz = 3840 samples → nanoseconds
        val preRollNs = 80_000_000L
        val csd1 = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        csd1.putLong(preRollNs); csd1.flip()
        // CSD-2: seek pre-roll (same)
        val csd2 = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        csd2.putLong(preRollNs); csd2.flip()

        val format = android.media.MediaFormat.createAudioFormat("audio/opus", sampleRate, channels)
        format.setByteBuffer("csd-0", csd0)
        format.setByteBuffer("csd-1", csd1)
        format.setByteBuffer("csd-2", csd2)

        val codec = android.media.MediaCodec.createDecoderByType("audio/opus")
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmOut = java.io.ByteArrayOutputStream()
        val timeoutUs = 10_000L
        var pktIdx = 0
        var presentUs = 0L
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                // 投递输入
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        if (pktIdx < packets.size) {
                            val pkt = packets[pktIdx++]
                            buf.clear()
                            buf.put(pkt)
                            codec.queueInputBuffer(inIdx, 0, pkt.size, presentUs, 0)
                            presentUs += 20_000L   // 20ms/帧
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, presentUs,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                // 读取输出
                val info = android.media.MediaCodec.BufferInfo()
                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIdx >= 0) {
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    val buf = codec.getOutputBuffer(outIdx)!!
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    pcmOut.write(bytes)
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }

        val pcm = pcmOut.toByteArray()
        if (pcm.isEmpty()) throw IllegalStateException(L.s(R.string.e_opus_empty))

        val wav = ByteArray(44 + pcm.size)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(wav, 0)
        wav.writeU32(36 + pcm.size, 4)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(wav, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(wav, 12)
        wav.writeU32(16, 16)
        wav.writeU16(1, 20)           // PCM
        wav.writeU16(channels, 22)
        wav.writeU32(sampleRate, 24)
        wav.writeU32(sampleRate * 2 * channels, 28)
        wav.writeU16(2 * channels, 32)
        wav.writeU16(16, 34)
        "data".toByteArray(Charsets.US_ASCII).copyInto(wav, 36)
        wav.writeU32(pcm.size, 40)
        pcm.copyInto(wav, 44)
        return wav
    }

    /** 解析导出的 wem 文件名："{id}_{原名}.wem" / "{id}.wem" → (id, 名称) */
    fun parseWemFileName(name: String): Pair<Long?, String?> {
        var base = name
        if (base.endsWith(".wem", true)) base = base.dropLast(4)
        val m = Regex("^(\\d+)(?:_(.+))?$").find(base) ?: return null to null
        val id = m.groupValues[1].toLongOrNull()
        return id to m.groupValues[2].takeIf { it.isNotEmpty() }
    }

    /** 生成导出文件名 */
    fun exportName(id: Long, nameMap: Map<Long, String>): String {
        val n = nameMap[id]
        return if (n != null) "${id}_${n}.wem" else "${id}.wem"
    }

    // ---------- Ogg granule 修复（revorb 等效） ----------

    /**
     * 读 wem 总采样数：fmt 0x42 无 vorb 块时在 fmt 负载 +24；有 vorb 块时在 vorb 负载 +0。
     * 读不到返回 -1。
     */
    fun readWemSampleCount(data: ByteArray): Long {
        if (data.size < 12) return -1
        var pos = 12
        var fmtPayload = -1
        var vorbPayload = -1
        while (pos + 8 <= data.size) {
            val id = String(data, pos, 4, Charsets.US_ASCII)
            val sz = readU32(data, pos + 4)
            if (sz < 0 || pos + 8 + sz > data.size) return -1
            // RIFF chunk ID 固定 4 字节，fmt 后有填充空格（"fmt "）
            if (id == "fmt " && fmtPayload < 0) fmtPayload = pos + 8
            if (id == "vorb" && vorbPayload < 0) vorbPayload = pos + 8
            pos += 8 + sz
        }
        return when {
            vorbPayload >= 0 && vorbPayload + 4 <= data.size ->
                readU32(data, vorbPayload).toLong() and 0xFFFFFFFFL
            fmtPayload >= 0 && fmtPayload + 28 <= data.size ->
                readU32(data, fmtPayload + 24).toLong() and 0xFFFFFFFFL
            else -> -1
        }
    }

    /** 读 wem 采样率（fmt 块标准 WAVEFORMATEX 头 +4）；读不到返回 -1 */
    fun readWemSampleRate(data: ByteArray): Int {
        if (data.size < 12) return -1
        var pos = 12
        while (pos + 8 <= data.size) {
            val id = String(data, pos, 4, Charsets.US_ASCII)
            val sz = readU32(data, pos + 4)
            if (sz < 0 || pos + 8 + sz > data.size) return -1
            if (id == "fmt " && pos + 12 <= data.size) return readU32(data, pos + 12)
            pos += 8 + sz
        }
        return -1
    }

    /** 检测 wem 是否为流式截断预取（RIFF 声明大小超出实际存储字节数） */
    fun isTruncatedWem(data: ByteArray): Boolean {
        if (data.size < 12) return true
        if (String(data, 0, 4, Charsets.US_ASCII) != "RIFF") return false
        val declared = readU32(data, 4)
        return declared + 8 > data.size
    }

    /** Ogg CRC-32 查表（poly 0x04C11DB7，非反射，初值 0） */
    private val oggCrcTable = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var r = i shl 24
            for (bit in 0 until 8) {
                r = if (r and Int.MIN_VALUE != 0) (r shl 1) xor 0x04C11DB7 else r shl 1
            }
            table[i] = r
        }
    }

    /**
     * 修复 ww2ogg 输出的 Ogg granule 位置：本游戏 wem 为「无 granule」格式，
     * ww2ogg 输出的数据页 granule 全为 0，播放器会显示 0 秒且 MediaPlayer 不发声
     *（等效 revorb）。策略：数据页按字节占比线性插值（单调递增），最后一页精确等于
     * 总采样数（播放器以最后页 granule 计算时长），逐页重算 CRC。头三页（ID/注释/设置）
     * 不动。
     */
    fun fixOggGranules(ogg: ByteArray, totalSamples: Long): ByteArray {
        if (ogg.size < 27 || totalSamples <= 0) return ogg
        val out = ogg.copyOf()
        // 页遍历：记录 (header偏移, 段表长, payload长)
        class Pg(val h: Int, val nsegs: Int, val payload: Int)
        val pages = ArrayList<Pg>()
        var pos = 0
        while (pos + 27 <= out.size) {
            if (out[pos] != 'O'.code.toByte() || out[pos + 1] != 'g'.code.toByte() ||
                out[pos + 2] != 'g'.code.toByte() || out[pos + 3] != 'S'.code.toByte()) break
            val nsegs = out[pos + 26].toInt() and 0xFF
            if (pos + 27 + nsegs > out.size) break
            var payload = 0
            for (i in 0 until nsegs) payload += out[pos + 27 + i].toInt() and 0xFF
            if (pos + 27 + nsegs + payload > out.size) break
            pages.add(Pg(pos, nsegs, payload))
            pos += 27 + nsegs + payload
        }
        if (pages.size < 4) return ogg // 3 头页 + 至少 1 数据页，异常布局不动
        val dataPages = pages.subList(3, pages.size)
        val totalBytes = dataPages.sumOf { it.payload.toLong() }
        if (totalBytes <= 0) return ogg
        var cum = 0L
        var prev = 0L
        for ((idx, pg) in dataPages.withIndex()) {
            var granule = if (idx == dataPages.size - 1) totalSamples
            else (totalSamples * (cum + pg.payload) + totalBytes / 2) / totalBytes
            if (granule <= prev) granule = prev + 1
            if (granule > totalSamples) granule = totalSamples
            for (i in 0 until 8) {
                out[pg.h + 6 + i] = (granule ushr (8 * i)).toByte()
            }
            // 重算页 CRC（先把 CRC 字段清零再算整页）
            for (i in 0 until 4) out[pg.h + 22 + i] = 0
            var crc = 0
            val end = pg.h + 27 + pg.nsegs + pg.payload
            for (i in pg.h until end) {
                crc = (crc shl 8) xor oggCrcTable[((crc ushr 24) xor (out[i].toInt() and 0xFF)) and 0xFF]
            }
            for (i in 0 until 4) {
                out[pg.h + 22 + i] = (crc ushr (8 * i)).toByte()
            }
            prev = granule
            cum += pg.payload
        }
        return out
    }

    // ---------- OGG → WEM 编码（fmt 0x42 + 外部码书 ID，Wwise 2019 格式） ----------

    /**
     * packed_codebooks_aoTuV_603.bin 解析缓存。
     * 格式：前 N 个码书 blob（stripped，无 BCV 前缀），末尾 offset table（4字节×(N+1)）。
     * 调用 loadPackedCodebooks() 初始化后，cbIdByBytes 可用于 10-bit ID 查找。
     */
    private var pcbBytes: ByteArray? = null
    private var cbCount: Int = 0
    // key = ByteArray内容包装（避免引用比较），value = codebook ID (0-based)
    private var cbIdByBytes: HashMap<String, Int>? = null

    /**
     * 加载外部码书库（首次调用解析，后续复用同一字节数组则跳过）。
     * pcb 为 packed_codebooks_aoTuV_603.bin 的完整字节。
     */
    fun loadPackedCodebooks(pcb: ByteArray) {
        if (pcb === pcbBytes) return          // 同一实例无需重复解析
        pcbBytes = pcb
        cbIdByBytes = null

        // offset table 在文件末尾；每条 u32 LE，共 count+1 个偏移
        // 规则：tableAt = file.size - 4*(count+1)；count 从条目 [count] 偏移推算
        // 实际文件：size=74387，tableAt=71991，count=598（599 个 u32，对应 598 条码书）
        val fileSize = pcb.size
        // 找 tableAt：从末尾往前找第一个合理的 u32 序列
        // 已知：tableAt = fileSize - 4*(N+1)，且 pcb[tableAt..tableAt+4N]
        // 用二分：先读末 4 字节 = 最后一个 offset table 项 = fileSize（总大小？）
        // 实际验证：table[598]=71991（blob 区大小），table[0]=0
        // 所以最后 4 字节 = offset[count] = blob 区总大小 = tableAt
        val blobSize = readU32(pcb, fileSize - 4)  // 应等于 tableAt
        if (blobSize <= 0 || blobSize >= fileSize) throw IllegalArgumentException(L.s(R.string.e_pcb_format))
        val tableAt = blobSize
        val tableBytes = fileSize - tableAt
        if (tableBytes % 4 != 0) throw IllegalArgumentException(L.s(R.string.e_pcb_align))
        val n = tableBytes / 4 - 1  // 条目数
        cbCount = n

        val map = HashMap<String, Int>(n * 2)
        for (i in 0 until n) {
            val start = readU32(pcb, tableAt + i * 4)
            val end = readU32(pcb, tableAt + (i + 1) * 4)
            if (start < 0 || end < start || end > blobSize)
                throw IllegalArgumentException(L.s(R.string.tpl_pcb_oob, i))
            // key：hex string of the blob bytes（避免 ByteArray equals 问题）
            val key = pcbBlobKey(pcb, start, end - start)
            map[key] = i
        }
        cbIdByBytes = map
    }

    /** 把 packed_codebooks blob 字节转为可作 map key 的字符串 */
    private fun pcbBlobKey(data: ByteArray, start: Int, len: Int): String {
        // 用 ISO-8859-1 1:1 映射，无编码损失
        return String(data, start, len, Charsets.ISO_8859_1)
    }

    /**
     * 把标准 Vorbis OGG 编码为游戏可用 wem（Wwise 2019，fmt 0x42，外部码书 ID）。
     *
     * 格式对应 Wwise 2019 新格式（vgmstream "new format"）：
     *  - RIFF/WAVE，仅 fmt（0x42字节）+ data 两个 chunk，无 vorb 块
     *  - fmt[0x18..0x27]：Wwise private "GUID"区 = totalSamples(4)+loopStart(4)+
     *    loopEnd(4)+loopBeginExtra(2)+loopEndExtra(2)
     *  - fmt[0x28..]：seekTableSize=0、audioOffset、maxPacketSize、bs0/bs1 等
     *  - data：[u16 size][packet]×，setup 在前（8-bit count_minus1 + N×10-bit 外部码书 ID +
     *    剥离 type 字段后的 floors/residues/mappings/modes），音频包为 mod_packets 变换格式
     *
     * 须先调用 loadPackedCodebooks()；OGG 须由 aoTuV b6.03 编码（WwiseNative.pcmToOgg）
     * 以确保码书与 aoTuV 603 库匹配。fmt[0x1C]=0xDD 表示 mod_packets。
     */
    fun oggToWem(ogg: ByteArray, packedCodebooks: ByteArray): ByteArray {
        loadPackedCodebooks(packedCodebooks)
        val map = cbIdByBytes
            ?: throw IllegalStateException(L.s(R.string.e_pcb_init))

        val parsed = parseOggPages(ogg)
        val packets = parsed.packets
        if (packets.size < 4) throw IllegalArgumentException(L.s(R.string.x_ogg_few_prefix) + packets.size + "）")
        val idP = packets[0]
        val commentP = packets[1]
        val setupP = packets[2]
        if (idP.size < 30 || idP[0] != 1.toByte() || String(idP, 1, 6, Charsets.US_ASCII) != "vorbis")
            throw IllegalArgumentException(L.s(R.string.e_not_vorbis))
        if (commentP.isEmpty() || commentP[0] != 3.toByte())
            throw IllegalArgumentException(L.s(R.string.e_no_comment))
        if (setupP.isEmpty() || setupP[0] != 5.toByte())
            throw IllegalArgumentException(L.s(R.string.e_no_setup))

        val channels = idP[11].toInt() and 0xFF
        val sampleRate = readU32(idP, 12)
        val bitrateNominal = readU32(idP, 20)
        // Vorbis ID 头 LSB-first：低 nibble = bs0，高 nibble = bs1
        val bs0 = idP[28].toInt() and 0x0F
        val bs1 = (idP[28].toInt() shr 4) and 0x0F
        val totalSamples = parsed.pages.last().granule
        if (totalSamples <= 0) throw IllegalArgumentException(L.s(R.string.e_no_granule))
        if (sampleRate <= 0) throw IllegalArgumentException(L.s(R.string.x_rate_prefix) + sampleRate + "）")

        // Wwise 2019 channelMask 编码（低字节 = 声道数，高字节 = 布局类型）
        val channelMask = when (channels) {
            1 -> 0x00004101
            2 -> 0x00003102
            4 -> 0x00003F04  // 4ch：待验证，暂用合理推测值
            else -> throw IllegalArgumentException(L.s(R.string.x_ch_prefix) + channels + L.s(R.string.x_ch_suffix))
        }

        // setup 包：转换为外部码书 ID 格式（同时取回 mode 表供音频包变换）
        val setupT = transformSetupPacketExternal(setupP, map, channels)
        val setupWem = setupT.bytes
        // 音频包：mod_packets 位变换（剥 type/window 位）
        val audio = packets.subList(3, packets.size).map {
            transformAudioPacketMod(it, setupT.modeBits, setupT.blockFlags)
        }
        for (p in listOf(setupWem) + audio) {
            if (p.size >= 0x8000)
                throw IllegalArgumentException(L.s(R.string.x_pkt_prefix) + p.size + L.s(R.string.x_pkt_suffix))
        }

        // 扫描最大音频包大小
        val maxPktSize = audio.maxOfOrNull { it.size } ?: 0

        // data 区：setup + audio，每包 [u16 LE size][payload]
        val data = ByteArray(2 * (1 + audio.size) + setupWem.size + audio.sumOf { it.size })
        var dp = 0
        for (p in listOf(setupWem) + audio) {
            data[dp++] = (p.size and 0xFF).toByte()
            data[dp++] = ((p.size shr 8) and 0xFF).toByte()
            p.copyInto(data, dp)
            dp += p.size
        }

        val audioOffset = 2 + setupWem.size  // audio 包在 data 中的起始偏移

        // avgBytesPerSec
        val avgBps = if (bitrateNominal > 0) (bitrateNominal + 7) / 8
        else ((data.size.toLong() * sampleRate + totalSamples - 1) / totalSamples).toInt()

        // fmt 块（0x42 = 66 字节）
        val fmt = ByteArray(0x42)
        fmt.writeU16(0xFFFF, 0x00)                          // codec
        fmt.writeU16(channels, 0x02)
        fmt.writeU32(sampleRate, 0x04)
        fmt.writeU32(avgBps, 0x08)
        fmt.writeU16(0, 0x0C)                               // blockAlign
        fmt.writeU16(0, 0x0E)                               // bitsPerSample
        fmt.writeU16(0x0030, 0x10)                          // cbSize = 48
        fmt.writeU16(0, 0x12)                               // validBits
        fmt.writeU32(channelMask, 0x14)
        // fmt[0x18..0x27] = Wwise "GUID" 区（16 字节 = loop/总采样信息）
        fmt.writeU32(totalSamples.toInt(), 0x18)            // totalSamples
        fmt.writeU32(0x000000DD, 0x1C)                      // mod signal：0xDD = mod_packets 格式
        fmt.writeU32(data.size, 0x20)                       // loopEndPktOff = data 区大小
        fmt.writeU16(0, 0x24)                               // loopBeginExtra
        fmt.writeU16(0, 0x26)                               // loopEndExtra
        // fmt[0x28..0x41] = Wwise Vorbis extra（26 字节）
        fmt.writeU32(0, 0x28)                               // seekTableSize = 0
        fmt.writeU32(audioOffset, 0x2C)                     // audioOffset（相对 data 起始）
        fmt.writeU16(maxPktSize, 0x30)                      // maxPacketSize
        fmt.writeU16(0, 0x32)                               // lastGranuleExtra
        fmt.writeU32(0, 0x34)                               // decodeAllocSize（提示值，0 安全）
        fmt.writeU32(0, 0x38)                               // decodeX64AllocSize
        fmt.writeU32(0, 0x3C)                               // uid
        fmt[0x40] = bs0.toByte()
        fmt[0x41] = bs1.toByte()

        // 组装 RIFF：仅 fmt + data（无 vorb 块）
        val bodySize = 8 + fmt.size + 8 + data.size
        val out = ByteArray(12 + bodySize)
        var bp = 0
        fun putFourCC(s: String) { for (i in 0 until 4) out[bp + i] = s[i].code.toByte(); bp += 4 }
        fun putU32(v: Int) { out.writeU32(v, bp); bp += 4 }
        fun putBytes(b: ByteArray) { b.copyInto(out, bp); bp += b.size }

        putFourCC("RIFF"); putU32(4 + bodySize)
        putFourCC("WAVE")
        putFourCC("fmt "); putU32(fmt.size); putBytes(fmt)
        putFourCC("data"); putU32(data.size); putBytes(data)

        return out
    }

    /**
     * 生成的 wem 结构自检（fmt 0x42 外部码书格式）：
     * 验证 chunk 表、data 包链、setup 包 count 字段合理性。
     * 返回 null = 通过；否则返回中文错误说明。
     */
    fun validateWemStructure(wem: ByteArray): String? {
        if (wem.size < 12 || String(wem, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(wem, 8, 4, Charsets.US_ASCII) != "WAVE") return L.s(R.string.e_riff)
        var pos = 12
        var dataStart = -1
        var dataSize = 0
        while (pos + 8 <= wem.size) {
            val id = String(wem, pos, 4, Charsets.US_ASCII)
            val sz = readU32(wem, pos + 4)
            if (sz < 0 || pos + 8 + sz > wem.size) return L.s(R.string.x_chunk_prefix) + id + "）"
            if (id == "data") { dataStart = pos + 8; dataSize = sz }
            pos += 8 + sz
        }
        if (pos != wem.size) return L.s(R.string.e_chunk_cover)
        if (dataStart < 0) return L.s(R.string.e_no_data_chunk)
        var p = dataStart
        val end = dataStart + dataSize
        var first = true
        while (p < end) {
            if (p + 2 > end) return L.s(R.string.x_hdr_trunc) + (p - dataStart) + "）"
            val size = (wem[p].toInt() and 0xFF) or ((wem[p + 1].toInt() and 0xFF) shl 8)
            if (size == 0) return L.s(R.string.x_zero_pkt) + (p - dataStart) + "）"
            if (p + 2 + size > end) return L.s(R.string.x_pkt_oob) + (p - dataStart) + L.s(R.string.x_declared) + size + "）"
            if (first) {
                // 外部码书格式：第一字节 = count_minus1（0..255 均合法）
                val count = (wem[p + 2].toInt() and 0xFF) + 1
                if (count < 1 || count > 256) return L.s(R.string.x_setup_count) + count + "）"
                first = false
            }
            p += 2 + size
        }
        return if (p == end) null else L.s(R.string.e_chain_mismatch)
    }

    // ---------- OGG 解析 ----------

    private class OggPage(val granule: Long, val serial: Int, val segTable: ByteArray, val payload: Int, val payloadLen: Int)
    private class OggParsed(val pages: List<OggPage>, val packets: List<ByteArray>)

    private fun parseOggPages(buf: ByteArray): OggParsed {
        val pages = ArrayList<OggPage>()
        var pos = 0
        while (pos + 27 <= buf.size) {
            if (String(buf, pos, 4, Charsets.US_ASCII) != "OggS")
                throw IllegalArgumentException(L.s(R.string.x_page_hdr) + pos + "）")
            val granule = readU64(buf, pos + 6)
            val serial = readU32(buf, pos + 14)
            val nsegs = buf[pos + 26].toInt() and 0xFF
            if (pos + 27 + nsegs > buf.size) throw IllegalArgumentException(L.s(R.string.e_seg_trunc))
            var payloadLen = 0
            for (i in 0 until nsegs) payloadLen += buf[pos + 27 + i].toInt() and 0xFF
            val payload = pos + 27 + nsegs
            if (payload + payloadLen > buf.size) throw IllegalArgumentException(L.s(R.string.e_page_trunc))
            pages.add(OggPage(granule, serial, buf.copyOfRange(pos + 27, pos + 27 + nsegs), payload, payloadLen))
            pos = payload + payloadLen
        }
        if (pos != buf.size) throw IllegalArgumentException(L.s(R.string.e_trailing))
        if (pages.isEmpty()) throw IllegalArgumentException(L.s(R.string.e_empty_ogg))
        if (pages.map { it.serial }.distinct().size != 1)
            throw IllegalArgumentException(L.s(R.string.e_chained_ogg))
        val packets = ArrayList<ByteArray>()
        var cur = ByteArray(0)
        var building = false
        for (pg in pages) {
            var off = pg.payload
            for (lacingB in pg.segTable) {
                val lacing = lacingB.toInt() and 0xFF
                if (!building) { cur = ByteArray(0); building = true }
                cur += buf.copyOfRange(off, off + lacing)
                off += lacing
                if (lacing < 255) { packets.add(cur); building = false }
            }
        }
        return OggParsed(pages, packets)
    }

    /** 读最后一个数据页的 granule（总采样数）；读不到返回 -1 */
    fun readOggTotalSamples(ogg: ByteArray): Long {
        return try {
            parseOggPages(ogg).pages.lastOrNull()?.granule ?: -1L
        } catch (e: Exception) { -1L }
    }

    // ---------- setup 包手术（标准 OGG → Wwise 外部码书 ID 格式） ----------

    /** LSB-first 逻辑位流（Vorbis 规范位序），位偏移相对于 start */
    private class LogicBits(private val b: ByteArray, private val start: Int, val byteLen: Int) {
        val bitLen = byteLen * 8
        fun get(p: Int): Boolean {
            if (p < 0 || p >= bitLen) throw IndexOutOfBoundsException(L.s(R.string.x_bit_oob) + p + "/" + bitLen + "）")
            return (b[start + (p shr 3)].toInt() shr (p and 7)) and 1 == 1
        }
        fun read(base: Int, n: Int): Int {
            var v = 0
            for (i in 0 until n) if (get(base + i)) v = v or (1 shl i)
            return v
        }
    }

    /** LSB-first 位写入缓冲 */
    private class BitWriter {
        private val buf = ArrayList<Byte>(256)
        private var cur = 0
        private var bits = 0

        fun write(value: Int, nBits: Int) {
            var v = value
            var n = nBits
            while (n > 0) {
                val space = 8 - bits
                val take = minOf(n, space)
                cur = cur or ((v and ((1 shl take) - 1)) shl bits)
                bits += take
                v = v ushr take
                n -= take
                if (bits == 8) { buf.add(cur.toByte()); cur = 0; bits = 0 }
            }
        }

        val bitCount: Int get() = buf.size * 8 + bits

        fun toByteArray(): ByteArray {
            val out = ByteArray(buf.size + if (bits > 0) 1 else 0)
            for (i in buf.indices) out[i] = buf[i]
            if (bits > 0) out[buf.size] = cur.toByte()
            return out
        }
    }

    private fun ilog(v: Int): Int {
        var x = v; var r = 0
        while (x != 0) { r++; x = x ushr 1 }
        return r
    }

    private fun maptype1Quantvals(entries: Int, dims: Int): Int {
        val bits = ilog(entries)
        var vals = entries shr ((bits - 1) * (dims - 1) / dims)
        while (true) {
            var acc = 1L; var acc1 = 1L
            for (i in 0 until dims) { acc *= vals.toLong(); acc1 *= (vals + 1).toLong() }
            if (acc <= entries && acc1 > entries) return vals
            if (acc > entries) vals-- else vals++
            if (vals < 1) throw IllegalArgumentException(L.s(R.string.e_quantvals))
        }
    }

    /**
     * 解析一个标准 OGG 码书（从 bits[p0] 开始，含 24-bit BCV 头），
     * 同时转换为 Wwise packed_codebooks 的紧凑 blob 格式，返回 (新 bit 偏移, blob 键)。
     *
     * 紧凑格式（对照 ww2ogg codebook.cpp rebuild()，Wwise 官方打包器约定）：
     *  dims(4), entries(14), ordered(1)；
     *  ordered: init_len-1(5), 逐段 count(ilog(entries-cur))；
     *  unordered: cll(3), sparse(1), 每条 [present(1) if sparse][len-1(cll)]；
     *  lookup(1)，为 1 时 q_min(32) q_delta(32) value_bits-1(4) seq(1) + quantvals×value_bits 值。
     *  cll = ilog(maxLen)（最大码字长度，经验证与全部 598 个库 blob 一致）。
     *  blob 字节长度 = floor(总位数/8)+1（位对齐时尾部多一个零字节）。
     */
    private fun codebookToCompactKey(bits: LogicBits, p0: Int): Pair<Int, String> {
        var p = p0
        val sync = bits.read(p, 24); p += 24
        if (sync != 0x564342)
            throw IllegalArgumentException(L.s(R.string.x_bad_sync) + sync.toString(16) + "）")
        val dims = bits.read(p, 16); p += 16
        val entries = bits.read(p, 24); p += 24
        if (entries <= 0 || dims <= 0) throw IllegalArgumentException(L.s(R.string.x_cb_params) + dims + " entries=" + entries + "）")
        if (dims > 15 || entries > 16383)
            throw IllegalArgumentException(L.s(R.string.x_cb_limit) + dims + " entries=" + entries + "）")
        val w = BitWriter()
        w.write(dims, 4)
        w.write(entries, 14)
        val ordered = bits.get(p); p += 1
        w.write(if (ordered) 1 else 0, 1)
        if (ordered) {
            val init = bits.read(p, 5); p += 5
            w.write(init, 5)
            var cur = 0
            while (cur < entries) {
                val n = ilog(entries - cur)
                val c = bits.read(p, n); p += n
                w.write(c, n)
                cur += c
                if (cur > entries) throw IllegalArgumentException(L.s(R.string.e_ordered_oob))
            }
        } else {
            val sparse = bits.get(p); p += 1
            val lens = IntArray(entries)
            var maxLen = 0
            for (i in 0 until entries) {
                var present = true
                if (sparse) { present = bits.get(p); p += 1 }
                if (present) {
                    val l = bits.read(p, 5) + 1; p += 5
                    lens[i] = l
                    if (l > maxLen) maxLen = l
                }
            }
            val cll = maxOf(1, ilog(maxLen))
            if (cll > 5) throw IllegalArgumentException(L.s(R.string.e_cll_range))
            // 紧凑格式：cll(3) 在前，sparse(1) 在后（ww2ogg rebuild 的读取顺序）
            w.write(cll, 3)
            w.write(if (sparse) 1 else 0, 1)
            for (i in 0 until entries) {
                if (sparse) w.write(if (lens[i] > 0) 1 else 0, 1)
                if (lens[i] > 0) w.write(lens[i] - 1, cll)
            }
        }
        val lookupType = bits.read(p, 4); p += 4
        if (lookupType == 2) throw IllegalArgumentException(L.s(R.string.e_lookup2))
        w.write(if (lookupType == 0) 0 else 1, 1)
        if (lookupType == 1) {
            val qMin = bits.read(p, 32); p += 32
            val qDelta = bits.read(p, 32); p += 32
            val valueBitsM1 = bits.read(p, 4); p += 4
            val seq = bits.read(p, 1); p += 1
            w.write(qMin, 32)
            w.write(qDelta, 32)
            w.write(valueBitsM1, 4)
            w.write(seq, 1)
            val qv = maptype1Quantvals(entries, dims)
            for (i in 0 until qv) {
                w.write(bits.read(p, valueBitsM1 + 1), valueBitsM1 + 1)
                p += valueBitsM1 + 1
            }
        }
        // blob 键：floor(nbits/8)+1 字节（尾部补零与库文件约定一致）
        val bytes = w.toByteArray()
        val blob = if (w.bitCount % 8 == 0) bytes + byteArrayOf(0) else bytes
        return Pair(p, String(blob, Charsets.ISO_8859_1))
    }

    /** setup 变换结果：WEM setup 包 + 音频包变换所需的 mode 信息 */
    private class SetupTransform(val bytes: ByteArray, val modeBits: Int, val blockFlags: BooleanArray)

    /**
     * setup 包转换：标准 OGG（libvorbis _vorbis_pack_books 语义）→ Wwise 外部码书 ID 格式。
     *
     * 码书 → 8-bit count_minus1 + N × 10-bit ID；随后按 Wwise 剥离规则重打包尾部
     * （对照 ww2ogg wwriff.cpp 非 full_setup 分支）：
     *  - 跳过 OGG 的 22-bit time-domain 占位（6+16 位 0）
     *  - floor：不写 16-bit type（恒为 1）
     *  - residue：type 只写 2 bit（OGG 为 16 bit）
     *  - mapping：不写 16-bit type（恒为 0）；2-bit 保留字段照抄
     *  - mode：不写 16-bit windowtype + 16-bit transformtype（恒为 0）
     *  - 不写 OGG 末尾的 1-bit framing
     */
    private fun transformSetupPacketExternal(
        setup: ByteArray,
        idMap: HashMap<String, Int>,
        channels: Int
    ): SetupTransform {
        if (String(setup, 1, 6, Charsets.US_ASCII) != "vorbis")
            throw IllegalArgumentException(L.s(R.string.e_bad_setup_hdr))
        val body = setup.copyOfRange(7, setup.size)
        val bits = LogicBits(body, 0, body.size)

        var p = 0
        val w = BitWriter()
        val countMinus1 = bits.read(p, 8); p += 8
        val count = countMinus1 + 1
        w.write(countMinus1, 8)

        for (c in 0 until count) {
            val (newP, key) = codebookToCompactKey(bits, p)
            val cbId = idMap[key]
            if (cbId == null) {
                val head = key.take(8).map { (it.code and 0xFF).toString(16).padStart(2, '0') }.joinToString("")
                android.util.Log.e("AudioToWem", "codebook miss #" + c + " keyLen=" + key.length + "B head=" + head)
                throw IllegalArgumentException(
                    L.s(R.string.tpl_cb_miss_a, c) + key.length + L.s(R.string.x_cb_miss_b) + head + "）")
            }
            w.write(cbId, 10)
            p = newP
        }

        // OGG time-domain 占位（6+16 位 0），WEM 不含，直接跳过
        p += 22

        // ---- floors（type 1）----
        val floorCountM1 = bits.read(p, 6); p += 6
        val floorCount = floorCountM1 + 1
        w.write(floorCountM1, 6)
        for (f in 0 until floorCount) {
            val ftype = bits.read(p, 16); p += 16
            if (ftype != 1) throw IllegalArgumentException(L.s(R.string.tpl_floor_type, ftype))
            val partitions = bits.read(p, 5); p += 5
            w.write(partitions, 5)
            val partitionClass = IntArray(partitions)
            var maxClass = 0
            for (j in 0 until partitions) {
                val pc = bits.read(p, 4); p += 4
                partitionClass[j] = pc
                if (pc > maxClass) maxClass = pc
                w.write(pc, 4)
            }
            val classDims = IntArray(maxClass + 1)
            for (j in 0..maxClass) {
                val dimsM1 = bits.read(p, 3); p += 3
                classDims[j] = dimsM1 + 1
                w.write(dimsM1, 3)
                val subclasses = bits.read(p, 2); p += 2
                w.write(subclasses, 2)
                if (subclasses != 0) {
                    val masterbook = bits.read(p, 8); p += 8
                    w.write(masterbook, 8)
                }
                for (k in 0 until (1 shl subclasses)) {
                    val sb = bits.read(p, 8); p += 8
                    w.write(sb, 8)
                }
            }
            val multM1 = bits.read(p, 2); p += 2
            w.write(multM1, 2)
            val rangebits = bits.read(p, 4); p += 4
            w.write(rangebits, 4)
            for (j in 0 until partitions) {
                val dims = classDims[partitionClass[j]]
                for (k in 0 until dims) {
                    w.write(bits.read(p, rangebits), rangebits)
                    p += rangebits
                }
            }
        }

        // ---- residues ----
        val resCountM1 = bits.read(p, 6); p += 6
        val resCount = resCountM1 + 1
        w.write(resCountM1, 6)
        for (r in 0 until resCount) {
            val rtype = bits.read(p, 16); p += 16
            if (rtype > 2) throw IllegalArgumentException(L.s(R.string.tpl_residue_type, rtype))
            w.write(rtype, 2)
            val begin = bits.read(p, 24); p += 24
            val end = bits.read(p, 24); p += 24
            val sizeM1 = bits.read(p, 24); p += 24
            val clsM1 = bits.read(p, 6); p += 6
            val classbook = bits.read(p, 8); p += 8
            w.write(begin, 24); w.write(end, 24); w.write(sizeM1, 24)
            w.write(clsM1, 6); w.write(classbook, 8)
            val classifications = clsM1 + 1
            val cascade = IntArray(classifications)
            for (j in 0 until classifications) {
                val low = bits.read(p, 3); p += 3
                w.write(low, 3)
                val bitflag = bits.get(p); p += 1
                w.write(if (bitflag) 1 else 0, 1)
                var high = 0
                if (bitflag) { high = bits.read(p, 5); p += 5; w.write(high, 5) }
                cascade[j] = high * 8 + low
            }
            for (j in 0 until classifications) {
                for (k in 0 until 8) {
                    if (cascade[j] and (1 shl k) != 0) {
                        val bk = bits.read(p, 8); p += 8
                        w.write(bk, 8)
                    }
                }
            }
        }

        // ---- mappings ----
        val mapCountM1 = bits.read(p, 6); p += 6
        val mapCount = mapCountM1 + 1
        w.write(mapCountM1, 6)
        val chBits = ilog(channels - 1)
        for (m in 0 until mapCount) {
            val mtype = bits.read(p, 16); p += 16
            if (mtype != 0) throw IllegalArgumentException(L.s(R.string.tpl_mapping_type, mtype))
            val submapsFlag = bits.get(p); p += 1
            w.write(if (submapsFlag) 1 else 0, 1)
            var submaps = 1
            if (submapsFlag) {
                val sm = bits.read(p, 4); p += 4
                submaps = sm + 1
                w.write(sm, 4)
            }
            val polar = bits.get(p); p += 1
            w.write(if (polar) 1 else 0, 1)
            if (polar) {
                val stepsM1 = bits.read(p, 8); p += 8
                w.write(stepsM1, 8)
                val steps = stepsM1 + 1
                for (j in 0 until steps) {
                    val mag = bits.read(p, chBits); p += chBits
                    val ang = bits.read(p, chBits); p += chBits
                    w.write(mag, chBits); w.write(ang, chBits)
                }
            }
            // 罕见的未被 Ak 移除的保留字段
            val reserved = bits.read(p, 2); p += 2
            if (reserved != 0) throw IllegalArgumentException(L.s(R.string.e_mapping_reserved))
            w.write(reserved, 2)
            if (submaps > 1) {
                for (j in 0 until channels) {
                    val mux = bits.read(p, 4); p += 4
                    w.write(mux, 4)
                }
            }
            for (j in 0 until submaps) {
                val tc = bits.read(p, 8); p += 8
                w.write(tc, 8)
                val fn = bits.read(p, 8); p += 8
                w.write(fn, 8)
                val rn = bits.read(p, 8); p += 8
                w.write(rn, 8)
            }
        }

        // ---- modes ----
        val modeCountM1 = bits.read(p, 6); p += 6
        val modeCount = modeCountM1 + 1
        w.write(modeCountM1, 6)
        val blockFlags = BooleanArray(modeCount)
        for (m in 0 until modeCount) {
            val bf = bits.get(p); p += 1
            blockFlags[m] = bf
            w.write(if (bf) 1 else 0, 1)
            // windowtype(16) + transformtype(16) 恒 0，OGG 有 WEM 无，跳过
            p += 32
            val mapping = bits.read(p, 8); p += 8
            w.write(mapping, 8)
        }
        // OGG 尾部 1-bit framing：WEM 不含，跳过

        return SetupTransform(w.toByteArray(), ilog(modeCount - 1), blockFlags)
    }

    /**
     * mod_packets 音频包变换（OGG → Wwise）：
     * 剥离 OGG 包头的 packet_type(1) + mode(mode_bits) 后重组为
     * [mode(mode_bits)][OGG 位流自 (1|3)+mode_bits 起]，长窗再剥 prev/next window 各 1 bit。
     * 对照 ww2ogg wwriff.cpp 1416-1502 的逆过程。
     */
    private fun transformAudioPacketMod(pkt: ByteArray, modeBits: Int, blockFlags: BooleanArray): ByteArray {
        if (pkt.isEmpty()) throw IllegalArgumentException(L.s(R.string.e_empty_audio_pkt))
        val bits = LogicBits(pkt, 0, pkt.size)
        if (bits.get(0)) throw IllegalArgumentException(L.s(R.string.e_pkt_type_bit))
        val mode = bits.read(1, modeBits)
        if (mode >= blockFlags.size) throw IllegalArgumentException(L.s(R.string.tpl_mode_oob, mode))
        val w = BitWriter()
        w.write(mode, modeBits)
        val srcStart = if (blockFlags[mode]) 3 + modeBits else 1 + modeBits
        for (i in srcStart until bits.bitLen) {
            w.write(if (bits.get(i)) 1 else 0, 1)
        }
        return w.toByteArray()
    }

    // ---------- 工具 ----------

    private fun ByteArray.writeU16(v: Int, off: Int) {
        this[off] = (v and 0xFF).toByte()
        this[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.writeU32(v: Int, off: Int) {
        this[off] = (v and 0xFF).toByte()
        this[off + 1] = ((v shr 8) and 0xFF).toByte()
        this[off + 2] = ((v shr 16) and 0xFF).toByte()
        this[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun readU32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}