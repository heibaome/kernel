package com.GhostKey.app.data

import com.GhostKey.app.data.ota.KallsymsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KallsymsExtractor（PC 端 tools/extract_rs/src/kallsyms_finder.rs recover 的移植）
 * 的合成镜像回归：按 kallsyms 真实布局逐字节构造 arm64 Image，覆盖
 * pre-6.4 / 6.4+ / ABSOLUTE_PERCPU / uleb128 长记录 / arm64 头声明尺寸 /
 * 双块歧义与 BTF 边界仲裁 / 全乱数据报错。
 *
 * fixture 的对抗性设计（对应真实镜像上踩过的坑）：
 *  - num_syms 与 names 之间留 4 字节零填充（6.1 GKI 实测布局，旧启发式在此断链）；
 *  - token 表按「0x21..0x7E 单字符 + 其余双字符」构造，'0'..9' 连续排布，
 *    与真机 token 表一样能被数字 needle 命中；
 *  - 双块镜像里两个 token 表各含一个「对方 flavor 下展开含空格」的差异 token，
 *    加上两块 names 记录长度不同（markers 数组随之不同），确保跨块误配对
 *    全部被 marker/字符校验拒绝，只留下真实的两个候选。
 */
class KallsymsExtractorRecoverTest {

    // 位模式 0xFFFFFFC080000000（Kotlin 不允许超出 Long.MAX_VALUE 的十六进制字面量）
    private val base = -0x3F80000000L

    private class Sym(val kind: Char, val name: String, val address: Long)

    // ---- 低层写入 ----

    private fun putU32(d: ByteArray, o: Int, v: Long) {
        d[o] = (v and 0xFF).toByte()
        d[o + 1] = ((v shr 8) and 0xFF).toByte()
        d[o + 2] = ((v shr 16) and 0xFF).toByte()
        d[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putU64(d: ByteArray, o: Int, v: Long) {
        for (k in 0 until 8) d[o + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }

    /**
     * 构造 256 项 token 表：0x21..0x7E 单字符（数字 needle 可命中），其余双字符。
     * flavor 决定差异 token：0x51 在 flavor 0 = "Qa"、flavor 1 = "Q "（展开含空格，
     * 进名字即非法）；0x52 在 flavor 0 = "R "、flavor 1 = "Rbb"。
     * 返回 (表字节, u16 索引数组)。
     */
    private fun buildTokenTable(flavor: Int): Pair<ByteArray, IntArray> {
        val offsets = IntArray(256)
        val out = java.io.ByteArrayOutputStream()
        fun writeToken(s: String) { // token 是 NUL 结尾串
            for (c in s) out.write(c.code)
            out.write(0)
        }
        for (i in 0 until 256) {
            offsets[i] = out.size()
            when {
                i == 0x51 && flavor == 0 -> writeToken("Qa")
                i == 0x51 && flavor == 1 -> writeToken("Q ")
                i == 0x52 && flavor == 0 -> writeToken("R ")
                i == 0x52 && flavor == 1 -> writeToken("Rbb")
                i in 0x21..0x7E -> writeToken(i.toChar().toString())
                else -> (0x21 + (i % 90)).let { c -> writeToken("${c.toChar()}${c.toChar()}") }
            }
        }
        return out.toByteArray() to offsets
    }

    /** names 记录流（支持 uleb128 双字节长度头）+ 每 256 条一个 marker（记录流偏移）。 */
    private fun buildNamesAndMarkers(symbols: List<Sym>, flavor: Int): Pair<ByteArray, IntArray> {
        // 无符号地址序（翻转符号位）：percpu 小地址排在内核高地址之前，与真实表一致
        val sorted = symbols.sortedBy { it.address xor Long.MIN_VALUE }
        val out = java.io.ByteArrayOutputStream()
        val markers = ArrayList<Int>()
        var recPos = 0
        sorted.forEachIndexed { idx, sym ->
            if (idx % 256 == 0) markers.add(recPos)
            val indices = ArrayList<Int>(sym.name.length + 2)
            indices.add(sym.kind.code) // 首字节展开为类型字符
            for (c in sym.name) {
                require(c.code in 0x21..0x7E) { "名字含非法字符: ${sym.name}" }
                // 差异 token 在本 flavor 下展开含空格，名字不得使用
                require(!(flavor == 0 && c.code == 0x52)) { "flavor0 名字含 'R': ${sym.name}" }
                require(!(flavor == 1 && c.code == 0x51)) { "flavor1 名字含 'Q': ${sym.name}" }
                indices.add(c.code)
            }
            val len = indices.size
            if (len < 0x80) {
                out.write(len)
                recPos += 1
            } else {
                out.write(0x80 or (len and 0x7F))
                out.write(len shr 7)
                recPos += 2
            }
            for (v in indices) {
                out.write(v)
                recPos += 1
            }
        }
        return out.toByteArray() to markers.toIntArray()
    }

    /** offsets 表：无符号 = addr-base；有符号 = percpu 绝对地址 / 普通符号负编码。 */
    private fun buildOffsets(symbols: List<Sym>, signed: Boolean): ByteArray {
        val sorted = symbols.sortedBy { it.address xor Long.MIN_VALUE }
        val out = ByteArray(sorted.size * 4)
        sorted.forEachIndexed { i, sym ->
            val v: Long = when {
                !signed -> sym.address - base
                // percpu 绝对地址是位模式为小正数的低地址；base 为负 Long
                sym.address >= 0 -> sym.address
                else -> -(sym.address - base) - 1L
            }
            putU32(out, 4 * i, v)
        }
        return out
    }

    /**
     * layoutB=false（pre-6.4）: [offsets][base][num+pad][names][markers][table][index]
     * layoutB=true （6.4+）  : [num+pad][names][markers][table][index][offsets][base]
     * 所有段间对齐填充为零；num_syms 与 names 之间固定 4 字节零填充。
     */
    private fun buildImage(
        flavor: Int,
        layoutB: Boolean,
        symbols: List<Sym>,
        signed: Boolean = false,
        headerPrefix: Boolean = false,
    ): ByteArray {
        val sorted = symbols.sortedBy { it.address xor Long.MIN_VALUE }
        val n = sorted.size
        val (table, tokenOffsets) = buildTokenTable(flavor)
        val index = ByteArray(512)
        for (i in 0 until 256) {
            index[2 * i] = (tokenOffsets[i] and 0xFF).toByte()
            index[2 * i + 1] = ((tokenOffsets[i] shr 8) and 0xFF).toByte()
        }
        val (names, markers) = buildNamesAndMarkers(sorted, flavor)
        val markersBytes = ByteArray(markers.size * 4)
        markers.forEachIndexed { i, m -> putU32(markersBytes, 4 * i, m.toLong()) }
        val offsets = buildOffsets(sorted, signed)
        val numSyms = ByteArray(8).also { putU32(it, 0, n.toLong()) }
        val baseBytes = ByteArray(8).also { putU64(it, 0, base) }

        val out = java.io.ByteArrayOutputStream()
        var pos = 0
        fun writeSection(b: ByteArray) { out.write(b); pos += b.size }
        fun padTo8() {
            val rem = pos and 7
            if (rem != 0) {
                out.write(ByteArray(8 - rem))
                pos += 8 - rem
            }
        }

        if (!layoutB) {
            writeSection(offsets); padTo8()
            writeSection(baseBytes)
            writeSection(numSyms)
            writeSection(names); padTo8()
            writeSection(markersBytes); padTo8()
            writeSection(table); padTo8()
            writeSection(index)
        } else {
            writeSection(numSyms)
            writeSection(names); padTo8()
            writeSection(markersBytes); padTo8()
            writeSection(table); padTo8()
            writeSection(index); padTo8()
            writeSection(offsets); padTo8()
            writeSection(baseBytes)
        }
        val body = out.toByteArray()
        if (!headerPrefix) return body
        val header = ByteArray(64)
        header[0x38] = 'A'.code.toByte()
        header[0x39] = 'R'.code.toByte()
        header[0x3A] = 'M'.code.toByte()
        header[0x3B] = 'd'.code.toByte()
        putU64(header, 0x10, body.size.toLong())
        return header + body
    }

    /**
     * 两遍构造：第一遍量出镜像总长，第二遍据此设 _end 地址
     * （_end-_text 必须恰等于 image_size 才能通过 decode_addresses 校验）。
     * headerPrefix 时声明尺寸为 body 长度（不含 64 字节头）。
     */
    private fun buildFixed(
        flavor: Int,
        layoutB: Boolean,
        signed: Boolean = false,
        headerPrefix: Boolean = false,
        symbols: (Long) -> List<Sym>,
    ): ByteArray {
        val first = buildImage(flavor, layoutB, symbols(0), signed, headerPrefix)
        val declared = if (headerPrefix) (first.size - 64).toLong() else first.size.toLong()
        return buildImage(flavor, layoutB, symbols(declared), signed, headerPrefix)
    }

    private val TARGETS = arrayOf(
        "do_timer_create", "prepare_creds", "selinux_state",
        "init_user_ns", "root_user", "init_groups", "init_ucounts",
    )

    /** 2048 个符号：锚符号 + 7 目标 + 填充 + _end（地址升序）。 */
    private fun standardSymbols(imageSize: Long): List<Sym> {
        val out = ArrayList<Sym>(2048)
        out.add(Sym('t', "_text", base))
        out.add(Sym('t', "load_module", base + 8))
        TARGETS.forEachIndexed { i, name -> out.add(Sym('d', name, base + 16 + 8L * i)) }
        for (i in 0 until 2048 - 2 - TARGETS.size - 1) {
            out.add(Sym('d', "sym_%05d".format(i), base + 80 + 8L * i))
        }
        out.add(Sym('d', "_end", base + imageSize))
        return out
    }

    @Test
    fun `pre-6_4 layout recovers all targets`() {
        val image = buildFixed(0, layoutB = false) { standardSymbols(it) }
        val r = KallsymsExtractor.extract(image)
        assertEquals(base, r.imageBase)
        assertEquals(2048, r.numSyms)
        for (name in TARGETS) assertNotNull("缺少 $name", r.symbols[name])
        assertEquals(base, r.symbols["_text"])
        assertEquals(base + image.size, r.symbols["_end"])
        assertEquals(base + 80, r.symbols["sym_00000"])
    }

    @Test
    fun `6_4+ layout recovers all targets`() {
        val image = buildFixed(0, layoutB = true) { standardSymbols(it) }
        val r = KallsymsExtractor.extract(image)
        assertEquals(base, r.imageBase)
        assertEquals(2048, r.numSyms)
        for (name in TARGETS) assertNotNull("缺少 $name", r.symbols[name])
        assertEquals(base + image.size, r.symbols["_end"])
    }

    @Test
    fun `signed percpu offsets decode to absolute addresses`() {
        val image = buildFixed(0, layoutB = false, signed = true) { imageSize ->
            val out = ArrayList<Sym>(2048)
            // 200 个 percpu 绝对低地址（10%，恰好满足 ≥90% 落镜像内的校验）
            for (i in 0 until 200) out.add(Sym('v', "pcpu_%03d".format(i), 0x1000 + 8L * i))
            out.add(Sym('t', "_text", base))
            out.add(Sym('t', "load_module", base + 8))
            TARGETS.forEachIndexed { i, name -> out.add(Sym('d', name, base + 16 + 8L * i)) }
            for (i in 0 until 2048 - 200 - 2 - TARGETS.size - 1) {
                out.add(Sym('d', "sym_%05d".format(i), base + 80 + 8L * i))
            }
            out.add(Sym('d', "_end", base + imageSize))
            out
        }
        val r = KallsymsExtractor.extract(image)
        assertEquals(base, r.imageBase)
        assertEquals(2048, r.numSyms)
        assertEquals(0x1000L, r.symbols["pcpu_000"])
        assertEquals(0x1008L, r.symbols["pcpu_001"])
        assertEquals(base + 80, r.symbols["sym_00000"]) // 负编码还原：base-1-(-81)
        assertNotNull(r.symbols["prepare_creds"])
    }

    @Test
    fun `uleb128 long name record decodes`() {
        val longName = "a".repeat(200) // 201 索引 → 双字节长度头 0xC9 0x01
        val image = buildFixed(0, layoutB = false) { imageSize ->
            val out = ArrayList<Sym>(2048)
            out.add(Sym('t', "_text", base))
            out.add(Sym('t', "load_module", base + 8))
            TARGETS.forEachIndexed { i, name -> out.add(Sym('d', name, base + 16 + 8L * i)) }
            for (i in 0 until 2048 - 4 - TARGETS.size) {
                out.add(Sym('d', "sym_%05d".format(i), base + 80 + 8L * i))
            }
            // 同址时稳定排序保持插入序：_end 在前，长名记录收尾（末条 u8/u8+uleb 错位）
            out.add(Sym('d', "_end", base + imageSize))
            out.add(Sym('d', longName, base + imageSize))
            out
        }
        val r = KallsymsExtractor.extract(image)
        assertEquals(base, r.imageBase)
        assertEquals(2048, r.numSyms)
        assertEquals(base + image.size, r.symbols[longName])
        assertEquals(base + image.size, r.symbols["_end"])
    }

    @Test
    fun `arm64 header declared size is honored`() {
        // 声明尺寸 = body 长度；文件整体多 64 字节头。
        // 若实现忽略头而用文件长度，_end-_text 校验必然失败。
        val image = buildFixed(0, layoutB = false, headerPrefix = true) { standardSymbols(it) }
        assertEquals(base + image.size - 64, KallsymsExtractor.extract(image).symbols["_end"])
    }

    @Test
    fun `garbage image fails with structural detail`() {
        val data = ByteArray(1 shl 20)
        var seed = 0x5EEDL
        for (i in data.indices) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            data[i] = (seed ushr 33).toByte()
        }
        val e = assertThrows(KallsymsExtractor.KallsymsError::class.java) {
            KallsymsExtractor.extract(data)
        }
        assertTrue(e.message!!.contains("未找到"))
    }

    @Test
    fun `two valid blocks are ambiguous without btf and resolved by btf boundary`() {
        fun symbolsB(imageSize: Long): List<Sym> {
            val out = ArrayList<Sym>(2048)
            out.add(Sym('t', "_text", base))
            out.add(Sym('t', "load_module", base + 8))
            TARGETS.forEachIndexed { i, name -> out.add(Sym('d', name, base + 16 + 8L * i)) }
            for (i in 0 until 2048 - 2 - TARGETS.size - 1) {
                out.add(Sym('d', "symRR_%04d".format(i), base + 80 + 8L * i))
            }
            out.add(Sym('d', "_end", base + imageSize))
            return out
        }

        fun symbolsA(imageSize: Long): List<Sym> {
            val out = ArrayList<Sym>(2048)
            out.add(Sym('t', "_text", base))
            out.add(Sym('t', "load_module", base + 8))
            TARGETS.forEachIndexed { i, name -> out.add(Sym('d', name, base + 16 + 8L * i)) }
            for (i in 0 until 2048 - 2 - TARGETS.size - 3) {
                out.add(Sym('d', "symQ_%04d".format(i), base + 80 + 8L * i))
            }
            // BTF 边界：反推 offset=1004、size=4092（非 8 倍数，避免与填充符号同址）
            out.add(Sym('d', "__start_BTF", base + 1004))
            out.add(Sym('d', "__stop_BTF", base + 5096))
            out.add(Sym('d', "_end", base + imageSize))
            return out
        }

        // blockB（flavor1）在前，blockA（flavor0）在后；两遍构造使两块的
        // _end 都等于整镜像长度（否则地址校验会先淘汰它们）
        var image = buildImage(1, layoutB = false, symbols = symbolsB(0)) +
            buildImage(0, layoutB = false, symbols = symbolsA(0))
        val total = image.size.toLong()
        image = buildImage(1, layoutB = false, symbols = symbolsB(total)) +
            buildImage(0, layoutB = false, symbols = symbolsA(total))

        // 无 BTF：blockA/blockB（外加共享 base 产生的跨块组合候选）→ 歧义
        val e = assertThrows(KallsymsExtractor.KallsymsError::class.java) {
            KallsymsExtractor.extract(image)
        }
        assertTrue(e.message!!.contains("歧义"))

        // BTF 边界命中 blockA（__start_BTF/_text == 1004 且 __stop-__start == 4092）
        val r = KallsymsExtractor.extract(image, 1004 to 4092)
        assertEquals(base + 1004, r.symbols["__start_BTF"])
        assertEquals(base + 5096, r.symbols["__stop_BTF"])
        // blockA 的 'Q' 经 flavor0 token "Qa" 展开
        assertNotNull(r.symbols["symQa_0000"])
        // blockB 的填充（flavor1 "Rbb" 展开）不在选中候选里
        assertFalse(r.symbols.containsKey("symRbbRbb_0000"))
        assertEquals(base, r.imageBase)
        assertEquals(2048, r.numSyms)
    }
}
