package com.GhostKey.app.data.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** parseAndroidBootHeader（大文件流式导入的头部解析）回归。 */
class BootHeaderParseTest {

    private fun header(version: Int, kernelSize: Int, headerSize: Int): ByteArray {
        val b = ByteArray(44)
        "ANDROID!".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        fun put(off: Int, v: Int) {
            for (k in 0..3) b[off + k] = ((v shr (8 * k)) and 0xFF).toByte()
        }
        put(8, kernelSize)
        put(20, headerSize)
        put(40, version)
        return b
    }

    @Test
    fun `v4 header yields page-aligned kernel offset`() {
        val h = BootImageParser().parseAndroidBootHeader(header(4, 0x2200000, 1580))!!
        assertEquals(4096, h.kernelOffset) // align(1580, 4096)
        assertEquals(0x2200000, h.kernelSize)
        assertEquals(4, h.version)
    }

    @Test
    fun `non-android or short input is rejected`() {
        assertNull(BootImageParser().parseAndroidBootHeader(ByteArray(10)))
        val garbage = header(4, 100, 1580)
        garbage[0] = 'X'.code.toByte()
        assertNull(BootImageParser().parseAndroidBootHeader(garbage))
        // v2 头不在此解析（走旧全量路径，由 decodeKernelImage 处理）
        assertNull(BootImageParser().parseAndroidBootHeader(header(2, 100, 1580)))
    }
}
