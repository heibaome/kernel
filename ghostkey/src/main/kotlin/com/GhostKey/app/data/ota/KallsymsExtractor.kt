package com.GhostKey.app.data.ota

/**
 * 纯 Kotlin kallsyms 符号表恢复器。
 *
 * 完整移植 PC 端 tools/extract_rs/src/kallsyms_finder.rs 的 recover 流程
 * （该算法已在 OnePlus Ace5 6.1.157 等真机镜像上验证），取代旧的
 * 「token_index 反推 + 双向夹逼」启发式 —— 旧链在 num_syms 后带 4 字节
 * 填充、token_table 与 token_index 间距超出小窗口的 6.1 GKI 布局上会整体
 * 断裂。流程：
 *
 *  1. [findTokenTables]：搜 "0\0..9\0" 数字 needle，枚举 8 对齐表起点，
 *     以「256 个 NUL 结尾可打印串 + u16 索引数组逐一相等」验证；
 *  2. [findNameTables]：在 token 表前 4MB 内 8 对齐扫描 markers（首项 0、
 *     相邻差 0x200..0x40000），再按 markers 尾值估计 names 尾部、向下回溯
 *     num_syms（u32 计数落在 markers 项数界内 + 4 字节零填充）；
 *  3. [parseNameSpans]：按记录走查 names（支持 uleb128 双字节长度转义），
 *     每 256 条必须命中对应 marker，末尾对齐到 markers 且填充为零；
 *  4. [decodeNames]：token 展开，要求类型字符合法、名字可打印，且必含
 *     _text / _end / load_module；
 *  5. [decodeAddressesFull]：pre-6.4（offsets 紧贴 relative_base 之前）与
 *     6.4+（offsets 在 token_index 之后）两种布局都试，要求地址表非降、
 *     恰有一个 _text==relative_base、_end-_text==image_size、≥90% 普通
 *     符号落在 [_text,_end] 内；
 *  6. 多候选仲裁：可选 BTF blob 边界（__start_BTF/__stop_BTF 反推出的
 *     偏移/长度与实际 BTF 位置一致者胜）；无 BTF 时唯一候选直接采用，
 *     多候选报歧义。
 */
internal object KallsymsExtractor {

    class KallsymsError(message: String) : RuntimeException(message)

    class Result(
        /** kallsyms_relative_base（内核镜像虚拟基址，如 0xFFFFFFC080000000）。 */
        val imageBase: Long,
        /** 去歧义后的符号表（同名不同址的符号已剔除）。 */
        val symbols: Map<String, Long>,
        val numSyms: Int,
    )

    /** kallsyms 必含的锚符号（PC 端 REQUIRED_SYMBOLS）。 */
    private val REQUIRED_SYMBOLS = arrayOf("_text", "_end", "load_module")

    private const val ALIGN = 8
    private const val TOKEN_COUNT = 256
    private const val TOKEN_INDEX_SIZE = TOKEN_COUNT * 2
    private const val MAX_TOKEN_LEN = 256
    private const val MARKER_SEARCH_WINDOW = 4 * 1024 * 1024
    private const val NAME_TAIL_SEARCH = 0x40000
    private const val MIN_MARKERS = 8
    private const val MAX_MARKERS = 4096

    /** 合法 kallsyms 类型字符（内核 scripts/kallsyms.c 的允许集合）。 */
    private val SYMBOL_TYPES = "aAbBcCdDeEfFgGiInNpPrRsStTuUvVwW?-".toByteArray()

    // ---- 带边界检查的小端读取（对应 Rust read_u16/u32/i32/u64 的 Option 语义） ----

    private fun u16At(d: ByteArray, o: Int): Int? {
        if (o < 0 || o + 2 > d.size) return null
        return (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32At(d: ByteArray, o: Int): Long? {
        if (o < 0 || o + 4 > d.size) return null
        return (d[o].toLong() and 0xFF) or
            ((d[o + 1].toLong() and 0xFF) shl 8) or
            ((d[o + 2].toLong() and 0xFF) shl 16) or
            ((d[o + 3].toLong() and 0xFF) shl 24)
    }

    private fun i32At(d: ByteArray, o: Int): Long? =
        u32At(d, o).let { if (it == null) null else if (it >= 0x80000000L) it - 0x100000000L else it }

    private fun u64At(d: ByteArray, o: Int): Long? {
        if (o < 0 || o + 8 > d.size) return null
        var r = 0L
        for (k in 7 downTo 0) r = (r shl 8) or (d[o + k].toLong() and 0xFF)
        return r
    }

    private fun alignUp(value: Int, alignment: Int): Int =
        (value + alignment - 1) and (alignment - 1).inv()

    /**
     * 按内核 `kallsyms_sym_address()` 语义把偏移表解码为绝对地址（移植自
     * PC 端 decode_addresses 的解码核心）：
     *  - 负偏移占比过半 → ABSOLUTE_PERCPU 编码：负值 = base - 1 - offset
     *    （普通符号），正值 = 绝对地址（percpu 段，低地址）；
     *  - 否则 = BASE_RELATIVE 无符号：address = base + u32(offset)。
     * 地址表必须非降（kallsyms 按地址排序）；不满足返回 null（候选无效）。
     */
    internal fun decodeAddresses(d: ByteArray, off: Int, numSyms: Int, base: Long): LongArray? {
        if (off < 0 || off.toLong() + 4L * numSyms > d.size) return null
        var negatives = 0
        for (i in 0 until numSyms) {
            if (i32At(d, off + 4 * i)!! < 0) negatives++
        }
        val signedMode = negatives * 2 >= numSyms
        val addresses = LongArray(numSyms)
        for (i in 0 until numSyms) {
            val v = i32At(d, off + 4 * i)!!
            addresses[i] = if (signedMode) {
                if (v < 0) base - 1 - v else v
            } else {
                base + (v and 0xFFFFFFFFL)
            }
        }
        // kallsyms 按地址排序；表可跨 percpu 低地址与内核高地址（位模式），
        // 必须按无符号比较（对应 Rust 的 u64 比较）
        for (i in 1 until numSyms) {
            if (java.lang.Long.compareUnsigned(addresses[i - 1], addresses[i]) > 0) return null
        }
        return addresses
    }

    /** 子数组搜索（对应 Rust find_subslice；返回首个 ≥ start 的命中）。 */
    private fun findSubslice(data: ByteArray, needle: ByteArray, start: Int): Int? {
        if (needle.isEmpty() || start > data.size) return null
        val limit = data.size - needle.size
        var i = start
        while (i <= limit) {
            if (data[i] == needle[0]) {
                var j = 1
                while (j < needle.size && data[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return null
    }

    /**
     * arm64 Image 头 0x10 处声明的镜像尺寸（偏移 0x38 处须有 "ARM\x64" 魔数）。
     * 声明值可能大于实际存放字节：vendor boot.img 打包常把尾部零（.bss/填充）
     * 从 Image 里裁掉。
     */
    private fun arm64ImageSize(image: ByteArray): Long? {
        if (image.size < 64) return null
        if (image[0x38] != 'A'.code.toByte() || image[0x39] != 'R'.code.toByte() ||
            image[0x3A] != 'M'.code.toByte() || image[0x3B] != 'd'.code.toByte()
        ) {
            return null
        }
        val size = u64At(image, 0x10) ?: return null
        return if (size != 0L) size else null
    }

    private class TokenTable(
        val tokens: List<ByteArray>,
        val tableOffset: Int,
        val indexOffset: Int,
    )

    /**
     * 校验 start 处的 256 项 token 表，其 '0' 项必须恰在 digitOffset
     * （数字 needle 的命中位置）。对应 Rust parse_token_table_at。
     */
    private fun parseTokenTableAt(image: ByteArray, start: Int, digitOffset: Int): TokenTable? {
        if (start % ALIGN != 0) return null
        val tokens = ArrayList<ByteArray>(TOKEN_COUNT)
        val tokenOffsets = IntArray(TOKEN_COUNT)
        val tokenStarts = IntArray(TOKEN_COUNT)
        var position = start
        for (i in 0 until TOKEN_COUNT) {
            if (position >= image.size) return null
            tokenStarts[i] = position
            val searchEnd = minOf(position + MAX_TOKEN_LEN + 1, image.size)
            var length = -1
            for (k in position until searchEnd) {
                if (image[k].toInt() == 0) {
                    length = k - position
                    break
                }
            }
            if (length <= 0) return null
            val end = position + length
            for (k in position until end) {
                val b = image[k].toInt() and 0xFF
                if (b < 0x20 || b > 0x7E) return null
            }
            tokenOffsets[i] = position - start
            tokens.add(image.copyOfRange(position, end))
            position = end + 1
        }

        if (tokenStarts['0'.code] != digitOffset) return null
        for (c in "0123456789_abcdefghijklmnopqrstuvwxyzT") {
            val token = tokens[c.code]
            if (token.size != 1 || token[0] != c.code.toByte()) return null
        }

        val indexOffset = alignUp(position, ALIGN)
        for (k in position until indexOffset) {
            if (image[k].toInt() != 0) return null
        }
        val indexEnd = indexOffset + TOKEN_INDEX_SIZE
        if (indexEnd > image.size || tokenOffsets[TOKEN_COUNT - 1] > 0xFFFF) return null
        for (i in 0 until TOKEN_COUNT) {
            if (u16At(image, indexOffset + i * 2) != tokenOffsets[i]) return null
        }
        return TokenTable(tokens, start, indexOffset)
    }

    /**
     * 扫描镜像中的 token 表：先找 "0\0..9\0" 数字串，再枚举一切可能把
     * '0' 项放到该处的 8 对齐表起点。对应 Rust find_token_tables。
     */
    private fun findTokenTables(image: ByteArray): List<TokenTable> {
        val digitNeedle = ByteArray(20)
        for (v in 0..9) {
            digitNeedle[2 * v] = ('0' + v).code.toByte()
            digitNeedle[2 * v + 1] = 0
        }
        val tables = ArrayList<TokenTable>()
        var searchFrom = 0
        while (true) {
            val digitOffset = findSubslice(image, digitNeedle, searchFrom) ?: break
            val earliest = (digitOffset - '0'.code * (MAX_TOKEN_LEN + 1)).coerceAtLeast(0)
            var start = alignUp(earliest, ALIGN)
            while (start <= digitOffset) {
                parseTokenTableAt(image, start, digitOffset)?.let { tables.add(it) }
                start += ALIGN
            }
            searchFrom = digitOffset + 1
            if (searchFrom >= image.size) break
        }
        return tables
    }

    /**
     * 从 start 起读 kallsyms_markers：首项 0、随后每项与前一项之差落在单个
     * name 块（0x200..=0x40000）内；以 limit 为读界。对应 Rust read_markers。
     */
    private fun readMarkers(image: ByteArray, start: Int, limit: Long): IntArray {
        if (start % ALIGN != 0 || start.toLong() + 8 > limit) return IntArray(0)
        val first = u32At(image, start) ?: return IntArray(0)
        val second = u32At(image, start + 4) ?: return IntArray(0)
        if (first != 0L || second < 0x200 || second > 0x40000) return IntArray(0)

        val markers = ArrayList<Int>(64)
        markers.add(0)
        markers.add(second.toInt())
        var position = start + 8
        while (markers.size < MAX_MARKERS && position.toLong() + 4 <= limit) {
            val value = u32At(image, position) ?: break
            val delta = value - markers[markers.size - 1]
            if (delta < 0x200 || delta > 0x40000) break
            markers.add(value.toInt())
            position += 4
        }
        return if (markers.size < MIN_MARKERS) IntArray(0) else markers.toIntArray()
    }

    /**
     * 走查 count 条长度前缀的 name 记录：每第 256 条必须命中对应 marker，
     * 且整块恰好在 markersOffset 结束（对齐填充为零）。
     * 返回交错的 (记录体偏移, 长度) 数组；对应 Rust parse_name_spans。
     */
    private fun parseNameSpans(
        image: ByteArray,
        namesOffset: Int,
        count: Int,
        markersOffset: Int,
        markers: IntArray,
        uleb128Lengths: Boolean,
    ): IntArray? {
        if (count == 0 || markers.size != (count + 255) / 256) return null

        val spans = IntArray(count * 2)
        var position = namesOffset
        for (index in 0 until count) {
            if (index % 256 == 0 && position - namesOffset != markers[index / 256]) return null
            if (position >= markersOffset) return null
            var length = image[position].toInt() and 0xFF
            position++
            if (uleb128Lengths && length and 0x80 != 0) {
                if (position >= markersOffset) return null
                val high = image[position].toInt() and 0xFF
                if (high and 0x80 != 0) return null
                length = (length and 0x7F) or (high shl 7)
                position++
            }
            if (length == 0 || length > 0x3FFF || position.toLong() + length > markersOffset) return null
            spans[2 * index] = position
            spans[2 * index + 1] = length
            position += length
        }

        if (alignUp(position, ALIGN) != markersOffset) return null
        for (i in position until markersOffset) {
            if (image[i].toInt() != 0) return null
        }
        return spans
    }

    /** 一块 names 的解码结果：类型字符 + 符号名，按记录顺序。 */
    private class Names(val kinds: ByteArray, val names: Array<String>) {
        val size: Int get() = kinds.size
    }

    /**
     * 把 token 下标序列展开成 (类型字符, 名字)，并要求真实 kallsyms 表必有的
     * 锚符号齐全。对应 Rust decode_names。
     */
    private fun decodeNames(image: ByteArray, spans: IntArray, tokens: List<ByteArray>): Names? {
        val count = spans.size / 2
        val kinds = ByteArray(count)
        val names = Array(count) { "" }
        for (i in 0 until count) {
            val position = spans[2 * i]
            val length = spans[2 * i + 1]
            var expandedLength = 0
            for (k in 0 until length) {
                val token = tokens.getOrNull(image[position + k].toInt() and 0xFF) ?: return null
                expandedLength += token.size
            }
            if (expandedLength > 4096) return null
            val expanded = ByteArray(expandedLength)
            var o = 0
            for (k in 0 until length) {
                val token = tokens[image[position + k].toInt() and 0xFF]
                token.copyInto(expanded, o)
                o += token.size
            }
            if (expandedLength == 0) return null
            val kind = expanded[0]
            if (!SYMBOL_TYPES.contains(kind)) return null
            if (expandedLength == 1) return null
            for (k in 1 until expandedLength) {
                val b = expanded[k].toInt() and 0xFF
                if (b < 0x21 || b > 0x7E) return null
            }
            val name = try {
                expanded.decodeToString(1, expandedLength, throwOnInvalidSequence = true)
            } catch (e: IllegalArgumentException) {
                return null
            }
            kinds[i] = kind
            names[i] = name
        }

        val present = HashSet<String>(count * 2)
        for (name in names) present.add(name)
        for (required in REQUIRED_SYMBOLS) {
            if (required !in present) return null
        }
        return Names(kinds, names)
    }

    private class NameTable(
        val numSymsOffset: Int,
        val names: Names,
    )

    /**
     * 恢复 names 块：在 token 表前搜 markers，再追查计数与填充都吻合的
     * num_syms，u8 与 uleb128 两种长度编码都解码。对应 Rust find_name_tables。
     */
    private fun findNameTables(image: ByteArray, tokens: List<ByteArray>, tokenTableOffset: Int): List<NameTable> {
        val lower = (tokenTableOffset - MARKER_SEARCH_WINDOW).coerceAtLeast(0)
        val markerCandidates = ArrayList<Pair<Int, IntArray>>()
        var position = alignUp(lower, ALIGN)
        while (position.toLong() + 8 <= tokenTableOffset) {
            val markers = readMarkers(image, position, tokenTableOffset.toLong())
            if (markers.isNotEmpty()) {
                markerCandidates.add(position to markers)
            }
            position += ALIGN
        }

        val recovered = ArrayList<NameTable>()
        for ((markersOffset, markers) in markerCandidates) {
            val minimumCount = (markers.size - 1) * 256 + 1
            val maximumCount = markers.size * 256
            val lastMarker = markers[markers.size - 1]
            if (lastMarker > markersOffset) continue
            val approximateNames = markersOffset - lastMarker
            val searchStart = (approximateNames - NAME_TAIL_SEARCH).coerceAtLeast(0)
            var numSymsOffset = approximateNames - approximateNames % ALIGN
            while (true) {
                if (numSymsOffset >= 0 && numSymsOffset + 8 <= image.size) {
                    val countValue = u32At(image, numSymsOffset) ?: 0L
                    val paddingIsZero = u32At(image, numSymsOffset + 4) == 0L
                    if (countValue in minimumCount.toLong()..maximumCount.toLong() && paddingIsZero) {
                        val count = countValue.toInt()
                        val namesOffset = numSymsOffset + ALIGN
                        val seenSpans = ArrayList<IntArray>()
                        for (uleb128Lengths in booleanArrayOf(true, false)) {
                            val spans = parseNameSpans(
                                image, namesOffset, count, markersOffset, markers, uleb128Lengths,
                            ) ?: continue
                            if (seenSpans.any { it.contentEquals(spans) }) continue
                            seenSpans.add(spans)
                            decodeNames(image, spans, tokens)?.let {
                                recovered.add(NameTable(numSymsOffset, it))
                            }
                        }
                    }
                }
                if (numSymsOffset < searchStart + ALIGN) break
                numSymsOffset -= ALIGN
            }
        }
        return recovered
    }

    private class RecoveredAddresses(
        val relativeBase: Long,
        val addresses: LongArray,
    )

    /**
     * 把 kallsyms_offsets + kallsyms_relative_base 解码为绝对地址，要求
     * 地址表非降且恰好张满整个镜像。对应 Rust decode_addresses 的完整校验。
     */
    private fun decodeAddressesFull(
        image: ByteArray,
        imageSize: Long,
        names: Names,
        offsetsOffset: Int,
        relativeBaseOffset: Int,
    ): RecoveredAddresses? {
        val count = names.size
        val offsetsEndL = offsetsOffset.toLong() + count * 4L
        if (offsetsEndL > image.size) return null
        val offsetsEnd = offsetsEndL.toInt()
        if (relativeBaseOffset < offsetsEnd || relativeBaseOffset.toLong() + 8 > image.size) return null
        for (i in offsetsEnd until relativeBaseOffset) {
            if (image[i].toInt() != 0) return null
        }
        val relativeBase = u64At(image, relativeBaseOffset) ?: return null
        if (relativeBase ushr 48 != 0xFFFFL || relativeBase and 0xFFFL != 0L) return null

        val signed = LongArray(count)
        var negatives = 0
        for (i in 0 until count) {
            val v = i32At(image, offsetsOffset + 4 * i) ?: return null
            signed[i] = v
            if (v < 0) negatives++
        }
        val addresses = LongArray(count)
        if (negatives * 2 >= count) {
            for (i in 0 until count) {
                val v = signed[i]
                addresses[i] = if (v < 0) relativeBase - 1 - v else v
            }
        } else {
            for (i in 0 until count) {
                val v = u32At(image, offsetsOffset + 4 * i) ?: return null
                addresses[i] = relativeBase + v
            }
        }

        // 地址表非降；percpu 低地址与内核高地址共存，按无符号比较（Rust u64）
        for (i in 1 until count) {
            if (java.lang.Long.compareUnsigned(addresses[i - 1], addresses[i]) > 0) return null
        }
        var textCount = 0
        var endCount = 0
        var textAddress = 0L
        var endAddress = 0L
        for (i in 0 until count) {
            when (names.names[i]) {
                "_text" -> { textCount++; textAddress = addresses[i] }
                "_end" -> { endCount++; endAddress = addresses[i] }
            }
        }
        if (textCount != 1 || endCount != 1) return null
        if (textAddress != relativeBase) return null
        if (endAddress - textAddress != imageSize) return null

        var ordinary = 0
        var inImage = 0
        for (i in 0 until count) {
            val kind = names.kinds[i]
            if (kind == 'A'.code.toByte() || kind == 'a'.code.toByte()) continue
            if (addresses[i] == 0L) continue
            ordinary++
            if (addresses[i] >= textAddress && addresses[i] <= endAddress) inImage++
        }
        if (ordinary == 0 || inImage * 100 < ordinary * 90) return null

        return RecoveredAddresses(relativeBase, addresses)
    }

    private class Candidate(
        val relativeBase: Long,
        val addresses: LongArray,
        val names: Names,
        val layout: String,
    ) {
        /** 恰有一条同名记录时返回其地址；重名（含同址）返回 null。 */
        fun resolve(name: String): Long? {
            var found: Long? = null
            for (i in 0 until names.size) {
                if (names.names[i] == name) {
                    if (found != null) return null
                    found = addresses[i]
                }
            }
            return found
        }
    }

    /**
     * 从未压缩 arm64 Image 恢复完整 kallsyms 表（对应 Rust recover）。
     *
     * @param btf 可选的镜像内 BTF blob (文件偏移, 总长)；多候选存活时，
     *            __start_BTF/__stop_BTF 能界定该 blob 的候选胜出。
     */
    private fun recover(image: ByteArray, btf: Pair<Int, Int>?): Result {
        val imageSize = arm64ImageSize(image) ?: image.size.toLong()
        val tokenTables = findTokenTables(image)
        val candidates = ArrayList<Candidate>()
        var nameBlocks = 0

        for (table in tokenTables) {
            for (nameTable in findNameTables(image, table.tokens, table.tableOffset)) {
                nameBlocks++
                val names = nameTable.names
                val count = names.size
                // pre-6.4：[offsets][relative_base][num_syms][pad][names]
                val oldRelativeBase = (nameTable.numSymsOffset - ALIGN).coerceAtLeast(0)
                val oldOffsets = (oldRelativeBase - alignUp(count * 4, ALIGN)).coerceAtLeast(0)
                // 6.4+：[.. names][markers][token_table][token_index][offsets][relative_base]
                val newOffsets = alignUp(table.indexOffset + TOKEN_INDEX_SIZE, ALIGN)
                val newRelativeBase = alignUp(newOffsets + count * 4, ALIGN)
                val layouts = arrayOf(
                    Triple("pre-6.4", oldOffsets, oldRelativeBase),
                    Triple("6.4+", newOffsets, newRelativeBase),
                )
                for ((layout, offsetsOffset, relativeBaseOffset) in layouts) {
                    val recovered = decodeAddressesFull(image, imageSize, names, offsetsOffset, relativeBaseOffset)
                        ?: continue
                    candidates.add(Candidate(recovered.relativeBase, recovered.addresses, names, layout))
                }
            }
        }

        if (candidates.isEmpty()) {
            // 完全没有 names 块 → kallsyms 被剥离/搬移；有 names 块但地址表
            // 全灭 → offsets/relative_base 或 image_size 布局异常。
            throw KallsymsError(
                "内核镜像中未找到 kallsyms 表" +
                    "（token 表 ${tokenTables.size} 个、names 块 $nameBlocks 个、image_size=0x${imageSize.toString(16)}）",
            )
        }

        val boundaryMatches = ArrayList<Int>()
        if (btf != null) {
            for ((index, candidate) in candidates.withIndex()) {
                val textBase = candidate.resolve("_text") ?: continue
                val btfStart = candidate.resolve("__start_BTF") ?: continue
                val btfStop = candidate.resolve("__stop_BTF") ?: continue
                if (btfStart - textBase == btf.first.toLong() &&
                    btfStop - btfStart == btf.second.toLong()
                ) {
                    boundaryMatches.add(index)
                }
            }
        }

        val selected = when {
            boundaryMatches.size == 1 -> candidates[boundaryMatches[0]]
            boundaryMatches.isEmpty() && candidates.size == 1 -> candidates[0]
            else -> throw KallsymsError(
                "kallsyms 恢复存在歧义" +
                    "（token 表 ${tokenTables.size} 个、完整候选 ${candidates.size} 个、BTF 边界命中 ${boundaryMatches.size} 个）",
            )
        }

        val symbols = HashMap<String, Long>(selected.names.size * 2)
        val ambiguous = HashSet<String>()
        for (i in 0 until selected.names.size) {
            val name = selected.names.names[i]
            val address = selected.addresses[i]
            val prev = symbols[name]
            if (prev != null && prev != address) {
                ambiguous.add(name)
            } else if (prev == null) {
                symbols[name] = address
            }
        }
        for (name in ambiguous) symbols.remove(name)
        return Result(selected.relativeBase, symbols, selected.names.size)
    }

    /**
     * 提取入口。失败抛 [KallsymsError]（消息含结构统计，供导入对话框展示）。
     *
     * @param btf 镜像内 BTF blob 的 (偏移, 总长)，见 [BootImageParser.findBtfBlob]。
     */
    internal fun extract(data: ByteArray, btf: Pair<Int, Int>? = null): Result = recover(data, btf)
}
