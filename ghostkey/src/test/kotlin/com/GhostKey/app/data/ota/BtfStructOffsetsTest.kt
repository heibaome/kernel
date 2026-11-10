package com.GhostKey.app.data.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * BTF 类型流推进回归（审计 B-01/B-02）。
 *
 * 历史缺陷：KIND_FUNC(12) 的 vlen 是 linkage（0/1/2），不是记录条数，
 * 旧实现按 `pos += 12 * vlen` 推进——vmlinux BTF 按编译单元顺序发射，
 * 全局函数（linkage=1）必然出现在 task_struct/cred 之前，第一条全局
 * FUNC 之后整条类型流即错位，task_cred/task_struct_size/cred_uid 全丢。
 * ENUM64(19)/DECL_TAG(17) 的 payload 也曾漏算。本测试用合成 BTF 固定
 * 推进规则：记录类型顺序与真实 vmlinux 同构（FUNC/ENUM64/DECL_TAG 在
 * 结构体之前），并验证成员缺失时宁可缺字段、不写 0 偏移。
 */
class BtfStructOffsetsTest {

    @Test
    fun `global FUNC and ENUM64 before task_struct keep the type stream aligned`() {
        val parser = BootImageParser()
        val layout = parser.structLayout(image(blob()), "task_struct")
        assertNotNull("task_struct 被吞（推进错位）", layout)
        assertEquals(16L, layout!!.size)
        assertEquals(8L, layout.memberOffsets["cred"])
        val cred = parser.structLayout(image(blob()), "cred")
        assertNotNull(cred)
        assertEquals(184L, cred!!.size)
        assertEquals(4L, cred.memberOffsets["uid"])
    }

    @Test
    fun `img parse produces BTF-derived offsets`() {
        val info = BootImageParser().parse(image(blob()))
        assertEquals("6.6.118-android15-8-test", info.release)
        val (doc, missing) = ImgToProfile.toProfileDocument(info, "boot.img")
        assertEquals(8L, doc.getLong("offset.task_cred"))
        assertEquals(16L, doc.getLong("offset.task_struct_size"))
        assertEquals(4L, doc.getLong("offset.cred_uid"))
        assertFalse(missing.contains("offset.task_cred"))
        // 合成镜像无 kallsyms：符号字段应报缺失，而不是静默
        assertTrue(missing.contains("offset.timer_link"))
    }

    @Test
    fun `missing task_struct cred member yields missing field instead of zero offset`() {
        val info = BootImageParser().parse(image(blob(withCredMember = false)))
        assertNull(info.structOffsets?.taskStruct)
        val (doc, missing) = ImgToProfile.toProfileDocument(info, "boot.img")
        assertNull(doc.getLong("offset.task_cred"))
        assertTrue(missing.contains("offset.task_cred"))
    }

    /** raw arm64 Image：偏移 56 处 ARM64 魔数 + linux_banner + 内嵌 BTF。 */
    private fun image(btf: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val head = ByteArray(64)
        "ARMd".forEachIndexed { i, c -> head[56 + i] = c.code.toByte() }
        out.write(head)
        out.write("Linux version 6.6.118-android15-8-test (build)\n".toByteArray(Charsets.US_ASCII))
        out.write(btf)
        out.write(ByteArray(16))
        return out.toByteArray()
    }

    private fun blob(withCredMember: Boolean = true): ByteArray {
        val b = BtfBlob()
        // type 1: INT（payload 4B）
        b.rec("int", kind = 1, vlen = 0, sizeOrType = 4, payload = ByteArray(4))
        // type 2: PTR -> INT
        b.rec("ptr", kind = 2, vlen = 0, sizeOrType = 1)
        // type 3: FUNC，vlen = 1（GLOBAL linkage）——旧实现在这里多跳 12 字节
        b.rec("f", kind = 12, vlen = 1, sizeOrType = 2)
        // type 4: ENUM64，vlen = 1（12B/条）
        b.rec("e64", kind = 19, vlen = 1, sizeOrType = 8, payload = ByteArray(12))
        // type 5: DECL_TAG，vlen = 0（payload 8B）
        b.rec("dt", kind = 17, vlen = 0, sizeOrType = 1, payload = ByteArray(8))
        // type 6: STRUCT task_struct
        val taskMembers = if (withCredMember) b.member("cred", typeId = 2, bitOffset = 64) else ByteArray(0)
        b.rec("task_struct", kind = 4, vlen = if (withCredMember) 1 else 0, sizeOrType = 16, payload = taskMembers)
        // type 7: PTR -> task_struct
        b.rec("ptr", kind = 2, vlen = 0, sizeOrType = 6)
        // type 8: STRUCT cred
        b.rec("cred", kind = 4, vlen = 1, sizeOrType = 184, payload = b.member("uid", typeId = 1, bitOffset = 32))
        return b.build()
    }

    private fun put32(b: ByteArray, off: Int, v: Int) {
        for (k in 0..3) b[off + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }
}

/** 合成 BTF blob 构造器（顶层类：Kotlin 嵌套类非 inner 不能作 receiver）。 */
private class BtfBlob {
    private val types = ByteArrayOutputStream()
    private val strings = ByteArrayOutputStream().apply { write(0) }

    private fun str(s: String): Int {
        val off = strings.size()
        strings.write(s.toByteArray(Charsets.US_ASCII))
        strings.write(0)
        return off
    }

    fun rec(name: String?, kind: Int, vlen: Int, sizeOrType: Int, payload: ByteArray = ByteArray(0)) {
        put32(types, name?.let(::str) ?: 0)
        put32(types, (kind shl 24) or vlen)
        put32(types, sizeOrType)
        types.write(payload)
    }

    fun member(name: String, typeId: Int, bitOffset: Int): ByteArray {
        val b = ByteArrayOutputStream()
        put32(b, str(name)); put32(b, typeId); put32(b, bitOffset)
        return b.toByteArray()
    }

    fun build(): ByteArray {
        val typesB = types.toByteArray()
        val stringsB = strings.toByteArray()
        val hdr = ByteArray(24)
        hdr[0] = 0x9F.toByte(); hdr[1] = 0xEB.toByte(); hdr[2] = 1; hdr[3] = 0
        put32(hdr, 4, 24)
        put32(hdr, 8, 0)
        put32(hdr, 12, typesB.size)
        put32(hdr, 16, typesB.size)
        put32(hdr, 20, stringsB.size)
        return hdr + typesB + stringsB
    }

    private fun put32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun put32(b: ByteArray, off: Int, v: Int) {
        for (k in 0..3) b[off + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }
}
