package com.dvpl.modhelper.codec

import org.json.JSONObject

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
                "wem $id 越界（off=$offset size=$size）"
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
        val count = readU32(data, 0x34)
        val tableEnd = 0x38 + 20 * count
        val dataStart = tableEnd + 4
        if (count > 100_000 || dataStart > data.size) return null
        val entries = ArrayList<WemEntry>(count)
        for (i in 0 until count) {
            val o = 0x38 + 20 * i
            val id = readU32(data, o)
            val langId = readU32(data, o + 4)
            val size = readU32(data, o + 8)
            val offAbs = readU32(data, o + 12)
            if (offAbs + size > data.size) return null // 坏表
            entries.add(WemEntry(id.toLong(), langId.toLong(), size, offAbs))
        }
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
        if (bank.entries.isEmpty()) throw IllegalStateException("该 bank 不含媒体数据，无法重打包")
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

    // ---------- OGG → WEM 编码（fmt 0x28 + vorb 0x2A + 2 字节头包格式） ----------

    /**
     * 把标准 Vorbis OGG 编码为游戏可用 wem（Wwise Vorbis，内联完整码书）。
     *
     * 格式参照 ww2ogg 0.24 的 wwriff.cpp 逆向规格，已在 PC 上对 8 个样本
     * （mono/stereo × q3/q5/q8 + 5 个真实游戏音源往返）做到 PCM 逐字节一致：
     *  - fmt 0x28：codec 0xFFFF，ext 0x16，subtype 1ch=4/2ch=3/4ch=0x33，
     *    KSDATAFORMAT_SUBTYPE_Vorbis GUID
     *  - vorb 0x2A：totalSamples@0、mod_signal=0x4A@4、setupOffset=0@0x10、
     *    firstAudioOffset@0x14、uid=0@0x24、bs0@0x28、bs1@0x29
     *  - data：[u16 size][packet]×，setup 包在前（内联完整码书，无 "\x05vorbis"
     *    前缀、无 22 位 time-domain 占位），音频包字节直拷（无 granule）
     *
     * 限制：包 ≥ 0x8000 字节拒绝（Wwise 2 字节头格式上限）；码书查找表类型
     * 2/3 拒绝（ww2ogg 不支持）；仅 1/2/4 声道；不支持链式 Ogg。
     */
    fun oggToWem(ogg: ByteArray): ByteArray {
        val parsed = parseOggPages(ogg)
        val packets = parsed.packets
        if (packets.size < 4) throw IllegalArgumentException("OGG 数据包过少（" + packets.size + "）")
        val idP = packets[0]
        val commentP = packets[1]
        val setupP = packets[2]
        if (idP.size < 30 || idP[0] != 1.toByte() || String(idP, 1, 6, Charsets.US_ASCII) != "vorbis")
            throw IllegalArgumentException("不是 Vorbis 音频（ID 头缺失或损坏）")
        if (commentP.isEmpty() || commentP[0] != 3.toByte())
            throw IllegalArgumentException("注释头缺失（异常 OGG）")
        if (setupP.isEmpty() || setupP[0] != 5.toByte())
            throw IllegalArgumentException("设置头缺失（异常 OGG）")

        val channels = idP[11].toInt() and 0xFF
        val sampleRate = readU32(idP, 12)
        val bitrateNominal = readU32(idP, 20)
        // Vorbis ID 头按 LSB-first 位序打包：低 nibble = blocksize_0（小窗），高 nibble = blocksize_1
        val bs0 = idP[28].toInt() and 0x0F
        val bs1 = (idP[28].toInt() shr 4) and 0x0F
        val totalSamples = parsed.pages.last().granule
        if (totalSamples <= 0) throw IllegalArgumentException("无法确定总采样数（末页 granule=0，异常 OGG）")
        if (sampleRate <= 0) throw IllegalArgumentException("采样率异常（" + sampleRate + "）")

        val subtype = when (channels) {
            1 -> 4
            2 -> 3
            4 -> 0x33
            else -> throw IllegalArgumentException("不支持的声道数 " + channels + "（仅支持 1/2/4 声道）")
        }

        val setupWem = transformSetupPacket(setupP)
        val audio = packets.subList(3, packets.size)
        for (p in listOf(setupWem) + audio) {
            if (p.size >= 0x8000)
                throw IllegalArgumentException("音频包过大（" + p.size + "B ≥ 32KB 上限）：请用较低质量重新编码 OGG")
        }

        val data = ByteArray(2 * (1 + audio.size) + setupWem.size + audio.sumOf { it.size })
        var dp = 0
        for (p in listOf(setupWem) + audio) {
            data[dp++] = (p.size and 0xFF).toByte()
            data[dp++] = ((p.size shr 8) and 0xFF).toByte()
            p.copyInto(data, dp)
            dp += p.size
        }

        // fmt 块（0x28）
        val fmt = ByteArray(0x28)
        fmt.writeU16(0xFFFF, 0)
        fmt.writeU16(channels, 2)
        fmt.writeU32(sampleRate, 4)
        val avgBps = if (bitrateNominal > 0) (bitrateNominal + 7) / 8
        else ((data.size.toLong() * sampleRate + totalSamples - 1) / totalSamples).toInt()
        fmt.writeU32(avgBps, 8)
        fmt.writeU16(0, 10)
        fmt.writeU16(0, 12)
        fmt.writeU16(0x16, 0x10)
        fmt.writeU16(0, 0x12)
        fmt.writeU32(subtype, 0x14)
        // KSDATAFORMAT_SUBTYPE_Vorbis：01-00-00-00-00-00-10-00-80-00-00-AA-00-38-9B-71
        val guid = byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)
        guid.copyInto(fmt, 0x18)

        // vorb 块（0x2A）
        val vorb = ByteArray(0x2A)
        vorb.writeU32(totalSamples.toInt(), 0x00)
        vorb.writeU32(0x4A, 0x04)              // mod_signal：标准包格式
        vorb.writeU32(0, 0x08)
        vorb.writeU32(0, 0x0C)
        vorb.writeU32(0, 0x10)                 // setupOffset：完整 setup 内联在 data 里
        vorb.writeU32(2 + setupWem.size, 0x14) // firstAudioOffset（相对 data）
        vorb.writeU32(0, 0x18)
        vorb.writeU32(0, 0x1C)
        vorb.writeU32(0, 0x20)
        vorb.writeU32(0, 0x24)                 // uid
        vorb[0x28] = bs0.toByte()
        vorb[0x29] = bs1.toByte()

        val body = ByteArray(8 * 3 + fmt.size + vorb.size + data.size)
        var bp = 0
        fun putChunk(id: String, payload: ByteArray) {
            for (i in 0 until 4) body[bp + i] = id[i].code.toByte()
            bp += 4
            body.writeU32(payload.size, bp); bp += 4
            payload.copyInto(body, bp); bp += payload.size
        }
        putChunk("fmt ", fmt)
        putChunk("vorb", vorb)
        putChunk("data", data)

        val out = ByteArray(12 + body.size)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        out.writeU32(4 + body.size, 4)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(out, 8)
        body.copyInto(out, 12)
        return out
    }

    /**
     * 生成的 wem 结构自检：走一遍 chunk 表与 data 包链，确认
     * setup 包以码书同步开头、包头逐个衔接且恰好覆盖到 data 末尾。
     * 返回 null = 通过；否则返回中文错误说明。
     */
    fun validateWemStructure(wem: ByteArray): String? {
        if (wem.size < 12 || String(wem, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(wem, 8, 4, Charsets.US_ASCII) != "WAVE") return "RIFF 头损坏"
        var pos = 12
        var dataStart = -1
        var dataSize = 0
        while (pos + 8 <= wem.size) {
            val id = String(wem, pos, 4, Charsets.US_ASCII)
            val sz = readU32(wem, pos + 4)
            if (sz < 0 || pos + 8 + sz > wem.size) return "chunk 表越界（" + id + "）"
            if (id == "data") { dataStart = pos + 8; dataSize = sz }
            pos += 8 + sz
        }
        if (pos != wem.size) return "chunk 表未覆盖整个文件"
        if (dataStart < 0) return "缺少 data 块"
        var p = dataStart
        val end = dataStart + dataSize
        var first = true
        while (p < end) {
            if (p + 2 > end) return "包头截断（data 偏移 " + (p - dataStart) + "）"
            val size = (wem[p].toInt() and 0xFF) or ((wem[p + 1].toInt() and 0xFF) shl 8)
            if (size == 0) return "零长度包（data 偏移 " + (p - dataStart) + "）"
            if (p + 2 + size > end) return "包越界（data 偏移 " + (p - dataStart) + "，声明 " + size + "）"
            if (first) {
                val bits = LogicBits(wem, p + 2, size)
                val count = bits.read(0, 8) + 1
                if (count < 1 || count > 200) return "码书数量异常（" + count + "）"
                if (bits.read(8, 24) != 0x564342) return "码书同步标志损坏"
                first = false
            }
            p += 2 + size
        }
        return if (p == end) null else "包链长度与 data 块不符"
    }

    // ---------- OGG 解析 ----------

    private class OggPage(val granule: Long, val serial: Int, val segTable: ByteArray, val payload: Int, val payloadLen: Int)
    private class OggParsed(val pages: List<OggPage>, val packets: List<ByteArray>)

    private fun parseOggPages(buf: ByteArray): OggParsed {
        val pages = ArrayList<OggPage>()
        var pos = 0
        while (pos + 27 <= buf.size) {
            if (String(buf, pos, 4, Charsets.US_ASCII) != "OggS")
                throw IllegalArgumentException("Ogg 页头损坏（偏移 " + pos + "）")
            val granule = readU64(buf, pos + 6)
            val serial = readU32(buf, pos + 14)
            val nsegs = buf[pos + 26].toInt() and 0xFF
            if (pos + 27 + nsegs > buf.size) throw IllegalArgumentException("Ogg 段表被截断")
            var payloadLen = 0
            for (i in 0 until nsegs) payloadLen += buf[pos + 27 + i].toInt() and 0xFF
            val payload = pos + 27 + nsegs
            if (payload + payloadLen > buf.size) throw IllegalArgumentException("Ogg 页被截断")
            pages.add(OggPage(granule, serial, buf.copyOfRange(pos + 27, pos + 27 + nsegs), payload, payloadLen))
            pos = payload + payloadLen
        }
        if (pos != buf.size) throw IllegalArgumentException("文件末尾有多余数据（非完整 Ogg）")
        if (pages.isEmpty()) throw IllegalArgumentException("空的 Ogg 文件")
        if (pages.map { it.serial }.distinct().size != 1)
            throw IllegalArgumentException("包含多个逻辑比特流（不支持链式 Ogg）")
        val packets = ArrayList<ByteArray>()
        var cur = ByteArray(0)
        var building = false
        for (pg in pages) {
            var off = pg.payload
            for (lacingB in pg.segTable) {
                // Byte 是有符号的：255 会变 -1，必须先转无符号
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

    // ---------- setup 包手术（标准 OGG → Wwise 内联完整码书） ----------

    /** LSB-first 逻辑位流（Vorbis 规范位序），位偏移相对于 start */
    private class LogicBits(private val b: ByteArray, private val start: Int, val byteLen: Int) {
        val bitLen = byteLen * 8
        fun get(p: Int): Boolean {
            if (p < 0 || p >= bitLen) throw IndexOutOfBoundsException("位偏移越界（" + p + "/" + bitLen + "）")
            return (b[start + (p shr 3)].toInt() shr (p and 7)) and 1 == 1
        }
        fun read(base: Int, n: Int): Int {
            var v = 0
            for (i in 0 until n) if (get(base + i)) v = v or (1 shl i)
            return v
        }
    }

    private fun ilog(v: Int): Int {
        var x = v
        var r = 0
        while (x != 0) { r++; x = x ushr 1 }
        return r
    }

    /** codebook.h _book_maptype1_quantvals */
    private fun maptype1Quantvals(entries: Int, dims: Int): Int {
        val bits = ilog(entries)
        var vals = entries shr ((bits - 1) * (dims - 1) / dims)
        while (true) {
            var acc = 1L
            var acc1 = 1L
            for (i in 0 until dims) { acc *= vals.toLong(); acc1 *= (vals + 1).toLong() }
            if (acc <= entries && acc1 > entries) return vals
            if (acc > entries) vals-- else vals++
            if (vals < 1) throw IllegalArgumentException("码书 quantvals 计算异常")
        }
    }

    /**
     * 扫描标准 Vorbis setup 包里的码书段，返回码书段结束的位偏移
     *（对应 ww2ogg codebook.cpp copy() 的解析路径，仅支持查找表类型 0/1）。
     */
    private fun scanCodebooks(bits: LogicBits, base: Int): Int {
        var p = base
        val count = bits.read(p, 8) + 1
        p += 8
        for (c in 0 until count) {
            val id = bits.read(p, 24); p += 24
            if (id != 0x564342)
                throw IllegalArgumentException("码书同步标志损坏（第 " + c + " 个，读到 0x" + id.toString(16) + "）")
            val dims = bits.read(p, 16); p += 16
            val entries = bits.read(p, 24); p += 24
            if (entries <= 0 || dims <= 0)
                throw IllegalArgumentException("码书参数异常（第 " + c + " 个）")
            val ordered = bits.get(p); p += 1
            if (ordered) {
                p += 5
                var cur = 0
                while (cur < entries) {
                    val n = ilog(entries - cur)
                    cur += bits.read(p, n); p += n
                    if (cur > entries) throw IllegalArgumentException("有序码书长度表越界（第 " + c + " 个）")
                }
            } else {
                val sparse = bits.get(p); p += 1
                for (i in 0 until entries) {
                    var present = true
                    if (sparse) { present = bits.get(p); p += 1 }
                    if (present) p += 5
                }
            }
            val lookupType = bits.read(p, 4); p += 4
            when (lookupType) {
                0 -> {}
                1 -> {
                    p += 32 + 32
                    val valueBits = bits.read(p, 4); p += 4
                    p += 1
                    p += maptype1Quantvals(entries, dims) * (valueBits + 1)
                }
                else -> throw IllegalArgumentException(
                    "码书查找表类型 " + lookupType + " 不受支持：请用标准 libvorbis（ffmpeg 默认）重新编码 OGG")
            }
        }
        return p
    }

    /** setup 包手术：剥 7 字节 "\x05vorbis" 前缀、剥 22 位 time-domain 占位（应为 0），其余位原样保留 */
    private fun transformSetupPacket(setup: ByteArray): ByteArray {
        if (String(setup, 1, 6, Charsets.US_ASCII) != "vorbis")
            throw IllegalArgumentException("设置头损坏")
        val body = setup.copyOfRange(7, setup.size)
        val bits = LogicBits(body, 0, body.size)
        val end = scanCodebooks(bits, 0)
        if (end + 22 > bits.bitLen)
            throw IllegalArgumentException("设置包在码书段后截断（异常 OGG）")
        for (i in 0 until 22) {
            if (bits.get(end + i))
                throw IllegalArgumentException("time-domain 段非零（异常 OGG）")
        }
        val rest = end + 22
        val outBits = body.size * 8 - 22
        val out = ByteArray((outBits + 7) / 8)
        for (p in 0 until end) if (bits.get(p))
            out[p shr 3] = (out[p shr 3].toInt() or (1 shl (p and 7))).toByte()
        for (p in rest until bits.bitLen) {
            val q = p - 22
            if (bits.get(p)) out[q shr 3] = (out[q shr 3].toInt() or (1 shl (q and 7))).toByte()
        }
        return out
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
