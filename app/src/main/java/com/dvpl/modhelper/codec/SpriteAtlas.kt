package com.dvpl.modhelper.codec

/**
 * Gfx/UI 精灵描述文件（*.txt.dvpl 解包后的文本）的解析与序列化。
 *
 * 文本格式（\n 分行，行内空格分隔；全量 1998 个真实样本验证：
 * 无 \r、无空行、每帧行恰好 7 个数字 token + 可选帧名，不存在 rotation 列）：
 * ```
 * <atlasCount>
 * <atlas 文件名> × atlasCount 行        图集文件通常与 txt 同目录
 * <logW> <logH>                         逻辑画布尺寸（含打包时裁掉的透明边）
 * <frameCount>
 * <srcX> <srcY> <srcW> <srcH> <offX> <offY> <atlasIdx> [frameName]
 * ...
 * ```
 * 帧行字段：
 *  - srcX/Y/W/H：在图集位图上的裁取矩形
 *  - offX/Y    ：裁取块在逻辑画布内的左上角（即被裁掉的透明边宽度）
 *  - atlasIdx  ：图集序号（0-based）
 *  - frameName ：可选帧名；无名帧行通常带一个尾随空格（序列化时保留原样）
 *
 * 序列化保证字节级还原：未修改的行原样写回，只有被编辑过的行按字段重建。
 */
class SpriteFrame(
    val srcX: Int, val srcY: Int, val srcW: Int, val srcH: Int,
    val offX: Int, val offY: Int,
    val atlasIdx: Int,
    val name: String?,
    internal val original: String?,     // 未修改的原始行；null = 已修改，序列化时按字段重建
    internal val namelessTail: String   // 无名帧重建时的行尾（保留原始风格：" " 或 ""）
) {
    /** 是否越出图集位图边界 */
    fun outsideAtlas(atlasW: Int, atlasH: Int): Boolean =
        srcX < 0 || srcY < 0 || srcW < 1 || srcH < 1 ||
            srcX + srcW > atlasW || srcY + srcH > atlasH

    /** 修改帧（返回新实例；原始行丢弃 → 序列化时按字段重建该行） */
    fun edit(
        srcX: Int = this.srcX, srcY: Int = this.srcY,
        srcW: Int = this.srcW, srcH: Int = this.srcH,
        offX: Int = this.offX, offY: Int = this.offY,
        atlasIdx: Int = this.atlasIdx,
        name: String? = this.name
    ): SpriteFrame = SpriteFrame(srcX, srcY, srcW, srcH, offX, offY, atlasIdx, name, null, namelessTail)

    /** 以当前字段为原始行，生成“已保存”副本 */
    internal fun asSaved(): SpriteFrame =
        SpriteFrame(srcX, srcY, srcW, srcH, offX, offY, atlasIdx, name, line(), namelessTail)

    /** 重建（可能被编辑过的）行文本 */
    internal fun line(): String = buildString {
        append(srcX); append(' '); append(srcY); append(' ')
        append(srcW); append(' '); append(srcH); append(' ')
        append(offX); append(' '); append(offY); append(' ')
        append(atlasIdx)
        if (name != null) append(' ').append(name) else append(namelessTail)
    }
}

/**
 * 一个精灵描述文档（对应一个 txt 文件，可能是多图集、多帧）。
 */
class SpriteDoc private constructor(
    val atlasNames: List<String>,
    private val rawHeader: List<String>,     // 行 0（atlasCount）+ 图集名行，原样保留
    val frames: MutableList<SpriteFrame>,
    private val trailingNewline: Boolean,
    logW: Int, logH: Int, rawDims: String?,
    private val rawCountLine: String         // frameCount 行；帧数不可变，原样保留
) {
    var logW: Int = logW
        private set
    var logH: Int = logH
        private set
    private var rawDims: String? = rawDims   // null = 尺寸已被编辑

    /** 是否有未保存的修改 */
    val isDirty: Boolean
        get() = rawDims == null || frames.any { it.original == null }

    /** 修改逻辑画布尺寸 */
    fun editLogical(w: Int, h: Int) {
        if (w == logW && h == logH) return
        logW = w
        logH = h
        rawDims = null
    }

    /** 保存成功后调用：以当前字段为基准重置“已修改”标记 */
    fun markClean() {
        rawDims = "$logW $logH"
        for (i in frames.indices) frames[i] = frames[i].asSaved()
    }

    /** 序列化回文本（未修改部分字节级还原） */
    fun serialize(): String {
        val lines = ArrayList<String>(rawHeader.size + frames.size + 2)
        lines.addAll(rawHeader)
        lines.add(rawDims ?: "$logW $logH")
        lines.add(rawCountLine)
        for (f in frames) lines.add(f.original ?: f.line())
        val body = lines.joinToString("\n")
        return if (trailingNewline) body + "\n" else body
    }

    companion object {
        /**
         * 解析 txt 文本。
         * @throws IllegalArgumentException 格式不符
         */
        fun parse(text: String): SpriteDoc {
            var body = text
            if (body.startsWith("\uFEFF")) body = body.substring(1)
            val trailing = body.endsWith("\n")
            val lines = (if (trailing) body.dropLast(1) else body)
                .split('\n')
                .filter { it.isNotBlank() }
            if (lines.size < 4) throw IllegalArgumentException("sprite txt too short")
            val atlasCount = lines[0].trim().toIntOrNull()
                ?: throw IllegalArgumentException("bad atlasCount")
            if (atlasCount < 1 || lines.size < 3 + atlasCount + 1)
                throw IllegalArgumentException("bad header")
            val names = ArrayList<String>(atlasCount)
            for (i in 1..atlasCount) names.add(lines[i].trim())
            val dims = lines[1 + atlasCount].trim().split(Regex("\\s+"))
            if (dims.size != 2) throw IllegalArgumentException("bad dims line")
            val logW = dims[0].toIntOrNull() ?: throw IllegalArgumentException("bad logW")
            val logH = dims[1].toIntOrNull() ?: throw IllegalArgumentException("bad logH")
            val rawDims = lines[1 + atlasCount]
            val frameCount = lines[2 + atlasCount].trim().toIntOrNull()
                ?: throw IllegalArgumentException("bad frameCount")
            if (frameCount < 1) throw IllegalArgumentException("frameCount < 1")
            if (lines.size != 3 + atlasCount + frameCount)
                throw IllegalArgumentException("line count mismatch: ${lines.size}")
            val frames = ArrayList<SpriteFrame>(frameCount)
            for (i in 0 until frameCount) {
                val raw = lines[3 + atlasCount + i]
                val toks = raw.trim().split(Regex("\\s+"))
                if (toks.size < 7) throw IllegalArgumentException("short frame line: $raw")
                val v = IntArray(7)
                for (j in 0..5) v[j] = toks[j].toIntOrNull()
                    ?: throw IllegalArgumentException("bad frame int: ${toks[j]}")
                val idx = toks[6].toIntOrNull()
                    ?: throw IllegalArgumentException("bad atlasIdx: ${toks[6]}")
                if (idx < 0 || idx >= atlasCount)
                    throw IllegalArgumentException("atlasIdx out of range: $idx")
                v[6] = idx
                val name = if (toks.size > 7) toks.subList(7, toks.size).joinToString(" ") else null
                frames.add(
                    SpriteFrame(v[0], v[1], v[2], v[3], v[4], v[5], idx, name, raw,
                        if (name == null && raw.endsWith(" ")) " " else "")
                )
            }
            return SpriteDoc(
                names, lines.subList(0, 1 + atlasCount), frames, trailing,
                logW, logH, rawDims, lines[2 + atlasCount]
            )
        }
    }
}
