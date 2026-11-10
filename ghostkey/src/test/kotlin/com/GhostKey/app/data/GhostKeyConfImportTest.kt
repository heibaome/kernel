package com.GhostKey.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 端提取产出的 .conf 导入链回归：以「合成夹具」验证 HoconSupport →
 * LegacyProfileConverter → ProfileDocument 的完整解析链在 JVM 上可复现
 * ProfileImportManager.importProfile 的解析路径（store.save 之外的每一环）。
 *
 * 免责声明：夹具 gk-synthetic-6.1-fixture.conf 的全部取值为编造，不对应
 * 任何真实设备或内核；注释、负数、64 位量程等特性保留以保证覆盖不失真。
 */
class GhostKeyConfImportTest {

    private fun loadFixture(): String =
        javaClass.getResourceAsStream("/gk-synthetic-6.1-fixture.conf")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `synthetic conf parses and yields complete profile`() {
        val root = HoconSupport.parseObject(loadFixture())
        val canonical = LegacyProfileConverter.convertIfLegacy(root)

        val release = canonical["release"] as? String
        assertEquals("0.0.0-synthetic-test-00-synthetic00000000", release)

        val doc = ProfileDocument(release = release!!, fields = canonical)
        assertTrue("缺字段: ${doc.missingRequiredFields()}", doc.isComplete())

        val offset = canonical["offset"] as Map<*, *>
        assertEquals(-274000000000L, (offset["timer_link"] as Number).toLong())
        assertEquals(2080L, (offset["task_cred"] as Number).toLong())
        assertEquals(-510000000000L, (canonical["page_offset"] as Number).toLong())
    }

    @Test
    fun `synthetic conf round-trips through hocon render`() {
        val root = HoconSupport.parseObject(loadFixture())
        val canonical = LegacyProfileConverter.convertIfLegacy(root)
        val rendered = HoconSupport.render(canonical)

        val reparsed = ProfileDocument(
            release = canonical["release"] as String,
            fields = HoconSupport.parseObject(rendered),
        )
        assertTrue(reparsed.isComplete())
        assertEquals(-274000123456L, reparsed.getLong("offset.cred_link"))
    }
}
