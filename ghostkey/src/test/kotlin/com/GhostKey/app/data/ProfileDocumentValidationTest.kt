package com.GhostKey.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * profile 执行门测试（2.0.24）：
 * 1. 值域校验规则——BTF 错位时代写出的 0 偏移/错位偏移/静默截断值必须被拦下；
 * 2. schema_version 作废门——旧版导入（无版本戳）不可被接纳，新版导入带戳；
 * 3. 内置资产全量扫描——值域规则不得误伤任何合法机型 profile。
 *
 * 规则基准来自 native 默认值表 kernel_table.c offsets_default 与内置资产
 * （task_cred≈0x820/0x838、task_struct_size=4800、cred_uid=4、kitimer_node=0x78、
 * kitimer_size=264/0x108），全部数值在 Long 域判定，先于 u32 截断。
 *
 * 注意：内置资产以**十进制有符号**形式存符号地址（如 page_offset = -551903297536
 * 即 0xFFFFFF7F80000000），校验须在 Long 域比较而非字符串前缀。
 */
class ProfileDocumentValidationTest {

    /** 与内置资产同构的合法 profile（HOCON 文本，数值取自 6.1.157 资产）。 */
    private val validHocon = """
        release = "6.1.157-test"
        schema_version = 2
        vuln_type = "cve-2026-98260"
        recommend_shizuku = 1
        page_offset = -551903297536
        offset = {
            task_cred = 2104
            task_struct_size = 4800
            cred_uid = 4
            cred_size = 176
            cred_cap_inheritable = 40
            cred_cap_permitted = 48
            cred_cap_effective = 56
            cred_cap_bset = 64
            cred_cap_ambient = 72
            cred_security = 120
            cred_user = 128
            cred_user_ns = 136
            cred_ucounts = 144
            cred_groups = 152
            kitimer_size = 264
            timer_link = -274737806884
            cred_link = -274743423632
            selinux_link = -274707525464
            user_ns = -274709804368
            root_user = -274709803712
            groups = -274709798816
            ucounts = -274709797432
            kitimer_node = 120
        }
    """.trimIndent()

    private fun doc(text: String): ProfileDocument = ProfileDocument.parse(text)

    /** 覆盖字段：嵌套字段替换 offset 块内原值；顶层/可选字段插到 release 之前。 */
    private fun docWithField(hocon: String, name: String, value: String): String {
        val nested = Regex("(?m)^(\\s*)$name = .*$")
        if (nested.containsMatchIn(hocon)) {
            return hocon.replace(nested) { "${it.groupValues[1]}$name = $value" }
        }
        return hocon.replace("release = ", "$name = $value\n        release = ")
    }

    // ---- 值域规则 ----

    @Test
    fun `valid profile passes all validation`() {
        val d = doc(validHocon)
        assertTrue(d.validationErrors().isEmpty())
        assertTrue(d.missingRequiredFields().isEmpty())
        assertTrue(d.isComplete())
        assertEquals(2, d.schemaVersion)
        d.toKernelProfile() // 不抛
    }

    @Test
    fun `zero offset rejected - btf corruption signature`() {
        // B-02 修复前：成员缺失会写 0 偏移
        for (field in listOf("task_cred", "cred_uid", "kitimer_node")) {
            val d = doc(docWithField(validHocon, field, "0"))
            assertTrue("$field=0 应被拒", d.validationErrors().any { "偏移为 0" in it })
        }
    }

    @Test
    fun `silent truncation value rejected before toInt`() {
        // 复核发现的洞：0x1_0000_0208.toInt() == 0x208，看似合法
        val d = doc(docWithField(validHocon, "task_cred", "0x100000208"))
        assertTrue(d.validationErrors().any { "task_cred" in it && "超出合理范围" in it })
    }

    @Test
    fun `out of range values rejected`() {
        for ((field, value) in listOf(
            "task_struct_size" to "0x100",   // < 0x800
            "task_struct_size" to "0x20000", // > 0x8000
            "cred_uid" to "0x400",           // >= 0x400
            "kitimer_size" to "0x40",        // < 0x80
            "kitimer_node" to "264",         // >= kitimer_size(264)
            "cred_size" to "0x1000",         // >= 0x400
            "cred_security" to "0x800",      // >= 0x400
        )) {
            val d = doc(docWithField(validHocon, field, value))
            assertTrue("$field=$value 应被拒，得到 ${d.validationErrors()}", d.validationErrors().isNotEmpty())
        }
    }

    @Test
    fun `task_cred beyond task_struct_size rejected`() {
        val d = doc(docWithField(validHocon, "task_cred", "0x2000"))
        assertTrue(d.validationErrors().any { "task_struct_size" in it })
    }

    @Test
    fun `user-space symbol address rejected`() {
        // 符号地址换成 0 / 用户态值应被拒；合法十进制负数形式不受影响
        val bad = doc(docWithField(validHocon, "page_offset", "0"))
        assertTrue(bad.validationErrors().any { "page_offset" in it })
        val badLink = doc(docWithField(validHocon, "timer_link", "0x1234"))
        assertTrue(badLink.validationErrors().any { "timer_link" in it })
        val d = doc(validHocon)
        assertTrue(d.validationErrors().isEmpty())
    }

    @Test
    fun `task_alloc_link zero allowed - non-zero must be kernel address`() {
        val withZero = doc(docWithField(validHocon, "task_alloc_link", "0"))
        assertTrue(withZero.validationErrors().isEmpty())
        val withGarbage = doc(docWithField(validHocon, "task_alloc_link", "0x1234"))
        assertTrue(withGarbage.validationErrors().any { "task_alloc_link" in it })
    }

    // ---- schema_version 作废门 ----

    @Test
    fun `missing schema_version parses as legacy zero and is inadmissible`() {
        val legacy = validHocon.replace("schema_version = 2\n", "")
        val d = doc(legacy)
        assertEquals(0, d.schemaVersion)
        // 值本身合法，但旧版生成的 profile 不得通过执行门
        assertFalse("旧版 profile 不得通过执行门", StoredProfile("x.conf", d).admissible)
    }

    @Test
    fun `fromKernelProfile round-trip carries current stamp`() {
        val kp = doc(validHocon).toKernelProfile()
        val exported = ProfileDocument.fromKernelProfile(kp)
        assertEquals(ProfileDocument.SCHEMA_VERSION_CURRENT, exported.schemaVersion)
        assertEquals(
            ProfileDocument.SCHEMA_VERSION_CURRENT,
            exported.getLong("schema_version")!!.toInt(),
        )
        assertTrue(StoredProfile("x.conf", exported).admissible)
    }

    // ---- 内置资产全量扫描（防规则误伤）----

    @Test
    fun `all builtin asset profiles pass validation`() {
        val dir = File("src/main/assets/ghostkey_kernel_profiles")
        if (!dir.isDirectory) return // 非模块根运行时跳过
        val includer: ((String) -> String?)? = { path ->
            File(dir, path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }
        var checked = 0
        for (f in dir.listFiles { it.isFile && it.name.endsWith(".conf") && it.name != "index.conf" }!!) {
            val d = ProfileDocument.parse(f.readText(Charsets.UTF_8), includer)
            val missing = d.missingRequiredFields()
            val invalid = d.validationErrors()
            assertTrue(
                "${f.name}: 缺失=$missing 值域=$invalid",
                missing.isEmpty() && invalid.isEmpty(),
            )
            // 资产豁免版本门（随 App 发布、非 Kotlin BTF 导入路径），但值域必须全过
            checked++
        }
        assertTrue("资产扫描应覆盖全部机型 profile（得到 $checked 个）", checked >= 13)
    }
}
