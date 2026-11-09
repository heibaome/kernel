package com.GhostKey.app.data

import com.GhostKey.app.data.ota.KallsymsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** decodeAddresses 的两种编码解码 + 排序校验（对应内核 kallsyms_sym_address 语义）。 */
class KallsymsExtractorDecodeTest {

    private val base = 0xFFFFFFC080000000L

    private fun s32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun table(values: List<Int>): ByteArray =
        values.flatMap { s32(it).toList() }.toByteArray()

    @Test
    fun `signed percpu table decodes to absolute addresses`() {
        // 60 负（普通符号，base-1-offset）+ 40 正（percpu 绝对低地址）：占比过半 → 有符号模式
        val positives = (1..40).map { it * 0x1000 }          // 低地址，表按地址排序故排最前
        val negatives = (40 downTo 1).map { -(it * 0x1000) } // 越负地址越高 → 非降
        val data = table(positives + negatives)
        val addresses = KallsymsExtractor.decodeAddresses(data, 0, 100, base)!!
        assertEquals(40, addresses[0])
        assertEquals(base - 1 + 0x1000, addresses[40])
        assertEquals(base - 1 + 40 * 0x1000, addresses[99])
    }

    @Test
    fun `unsigned relative table decodes to base plus offset`() {
        val values = (1..100).map { it * 0x100 }
        val data = table(values)
        val addresses = KallsymsExtractor.decodeAddresses(data, 0, 100, base)!!
        assertEquals(base + 0x100, addresses[0])
        assertEquals(base + 100 * 0x100, addresses[99])
    }

    @Test
    fun `disordered table is rejected`() {
        // 两个符号地址倒序 → kallsyms 不可能如此 → 候选无效
        val negatives = listOf(-0x2000, -0x1000)
        val data = table(negatives)
        assertNull(KallsymsExtractor.decodeAddresses(data, 0, 2, base))
    }

    @Test
    fun `out of bounds offset is rejected`() {
        val data = table(listOf(0x100))
        assertNull(KallsymsExtractor.decodeAddresses(data, 0, 100, base))
        assertNotNull(KallsymsExtractor.decodeAddresses(data, 0, 1, base))
    }
}
