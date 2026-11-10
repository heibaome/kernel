package com.GhostKey.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 端提取产出的 .conf 导入链回归：以 OnePlus Ace5 (6.1.157) 真实交付的
 * profile 为夹具，验证 HoconSupport → LegacyProfileConverter → ProfileDocument
 * 的完整解析链在 JVM 上可复现 ProfileImportManager.importProfile 的
 * 解析路径（store.save 之外的每一环）。
 */
class GhostKeyConfImportTest {

    private fun loadFixture(): String =
        javaClass.getResourceAsStream("/gk-ace5-6.1.157-fixture.conf")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `ace5 conf parses and yields complete profile`() {
        val root = HoconSupport.parseObject(loadFixture())
        val canonical = LegacyProfileConverter.convertIfLegacy(root)

        val release = canonical["release"] as? String
        assertEquals("6.1.157-android14-11-o-g7cdd565ce1ed", release)

        val doc = ProfileDocument(release = release!!, fields = canonical)
        assertTrue("缺字段: ${doc.missingRequiredFields()}", doc.isComplete())

        val offset = canonical["offset"] as Map<*, *>
        assertEquals(-274739184632L, (offset["timer_link"] as Number).toLong())
        assertEquals(2104L, (offset["task_cred"] as Number).toLong())
        assertEquals(-551903297536L, (canonical["page_offset"] as Number).toLong())
    }

    @Test
    fun `ace5 conf round-trips through hocon render`() {
        val root = HoconSupport.parseObject(loadFixture())
        val canonical = LegacyProfileConverter.convertIfLegacy(root)
        val rendered = HoconSupport.render(canonical)

        val reparsed = ProfileDocument(
            release = canonical["release"] as String,
            fields = HoconSupport.parseObject(rendered),
        )
        assertTrue(reparsed.isComplete())
        assertEquals(-274743323568L, reparsed.getLong("offset.cred_link"))
    }
}
