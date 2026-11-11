package com.GhostKey.app.data

import com.GhostKey.app.model.KernelProfile

/**
 * 通用 profile 文档（v2 布局）。
 *
 * 以 `Map<String, Any?>` 驱动，支持点分路径取值（如 `offset.timer_link`），
 * 兼容从 HOCON / JSON 解析出来的任意结构，并可无损渲染回 HOCON。
 *
 * 与 `KernelProfile` 的关系：
 *  - `KernelProfile` 是原生体 wire 模型（定长 blob），字段固定；
 *  - `ProfileDocument` 是通用容器，可表达任意内核的任意字段；
 *  - 通过 [toKernelProfile] / [fromKernelProfile] 双向转换，新内核无需改 Kotlin 代码。
 */
data class ProfileDocument(
    val release: String,
    val schemaVersion: Int = 1,
    val fields: Map<String, Any?>,
) {

    /** 点分路径取值（如 `offset.timer_link`）。返回 null 表示缺字段。 */
    fun get(path: String): Any? {
        val parts = path.split('.')
        var cur: Any? = fields
        for (p in parts) {
            if (cur !is Map<*, *>) return null
            cur = cur[p]
        }
        return cur
    }

    /** 点分路径取 Long（兼容 Long/Int/Double/十六进制或十进制字符串）。 */
    fun getLong(path: String): Long? = coerceLong(get(path))

    /** 点分路径取 String。 */
    fun getString(path: String): String? = get(path)?.toString()

    /** 点分路径取 Boolean（兼容 true/false/1/0/"1"/"0"）。 */
    fun getBoolean(path: String, default: Boolean = false): Boolean =
        when (val v = get(path)) {
            is Boolean -> v
            is Number -> v.toLong() != 0L
            is String -> v.trim() !in setOf("", "0", "false", "False", "FALSE")
            else -> default
        }

    /**
     * 校验是否为可执行的完整 profile。
     * 返回缺失的必填字段路径列表（空列表表示完整）。
     */
    fun missingRequiredFields(): List<String> {
        val missing = mutableListOf<String>()
        for (f in REQUIRED_NUMERIC) {
            if (getLong(f) == null) missing += f
        }
        if (release.isBlank()) missing += "release"
        return missing
    }

    /**
     * 值域校验（只检查**已提供**的值，缺失字段交给 [missingRequiredFields]）。
     *
     * 背景：2.0.15–2.0.18 的 BTF 解析错位会把垃圾偏移写进导入的 profile
     * （成员缺失时曾写 0，错位时写相邻成员的偏移），而旧校验只判键存在，
     * 污染值会畅通无阻地进内核写路径。规则与 native 默认值表
     * （kernel_table.c offsets_default）对齐，全部在 Long 域判定，
     * 先于 toKernelProfile 的 u32 截断——`0x1_0000_0208.toInt()` 这类
     * 静默截断值在这里就会被上界拦下。
     */
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()

        /** 双路径取值：v2 嵌套（offset.xxx）优先，回退扁平键（legacy）。 */
        fun num(name: String): Long? = getLong("offset.$name") ?: getLong(name)
        fun hex(v: Long): String = "0x${v.toString(16)}"

        for (p in SYMBOL_ADDRESS_FIELDS) {
            val v = getLong(p) ?: continue
            if (v < SYMBOL_ADDRESS_MIN) errors += "$p=${hex(v)}: 符号地址不在内核地址空间"
        }

        num("task_struct_size")?.let { v ->
            if (v < TASK_STRUCT_SIZE_MIN || v > TASK_STRUCT_SIZE_MAX) {
                errors += "task_struct_size=${hex(v)}: 超出合理范围"
            }
        }
        num("task_cred")?.let { v ->
            val size = num("task_struct_size")
            when {
                v <= 0 -> errors += "task_cred=${hex(v)}: 偏移为 0（BTF 错位典型产物）"
                v > TASK_STRUCT_SIZE_MAX -> errors += "task_cred=${hex(v)}: 超出合理范围"
                size != null && size > 0 && v >= size ->
                    errors += "task_cred=${hex(v)}: 不小于 task_struct_size=${hex(size)}"
            }
        }
        num("cred_uid")?.let { v ->
            if (v <= 0 || v >= CRED_MEMBER_MAX) errors += "cred_uid=${hex(v)}: 超出合理范围"
        }
        val kitimerSize = num("kitimer_size") ?: KITIMER_SIZE_DEFAULT
        num("kitimer_size")?.let { v ->
            if (v < KITIMER_SIZE_MIN || v > KITIMER_SIZE_MAX) {
                errors += "kitimer_size=${hex(v)}: 超出合理范围"
            }
        }
        num("kitimer_node")?.let { v ->
            when {
                v <= 0 -> errors += "kitimer_node=${hex(v)}: 偏移为 0（BTF 错位典型产物）"
                v >= kitimerSize -> errors += "kitimer_node=${hex(v)}: 不小于 kitimer_size=${hex(kitimerSize)}"
            }
        }
        num("cred_size")?.let { v ->
            if (v < CRED_SIZE_MIN || v > CRED_MEMBER_MAX) errors += "cred_size=${hex(v)}: 超出合理范围"
        }
        for (p in CRED_MEMBER_FIELDS) {
            val v = num(p) ?: continue
            if (v <= 0 || v >= CRED_MEMBER_MAX) errors += "$p=${hex(v)}: 超出合理范围"
        }
        num("task_alloc_link")?.let { v ->
            if (v > 0 && v < SYMBOL_ADDRESS_MIN) {
                errors += "task_alloc_link=${hex(v)}: 符号地址不在内核地址空间"
            }
        }
        return errors
    }

    /** 是否完整（必填齐备且值域合法）。 */
    fun isComplete(): Boolean =
        missingRequiredFields().isEmpty() && validationErrors().isEmpty()

    /** 转 KernelProfile；缺字段或值域非法抛 [ProfileConversionException]。 */
    fun toKernelProfile(): KernelProfile {
        val missing = missingRequiredFields()
        val invalid = validationErrors()
        if (missing.isNotEmpty() || invalid.isNotEmpty()) {
            val parts = buildList {
                if (missing.isNotEmpty()) add("缺少必填字段: ${missing.joinToString()}")
                invalid.take(8).forEach { add(it) }
            }
            throw ProfileConversionException(parts.joinToString("; "))
        }
        return KernelProfile(
            release = release,
            vulnType = getString("vuln_type") ?: "cve-2026-98260",
            recommendShizuku = getBoolean("recommend_shizuku", default = true),
            pageOffset = getLong("page_offset")!!,
            timerLink = getLong("offset.timer_link") ?: getLong("timer_link")!!,
            credLink = getLong("offset.cred_link") ?: getLong("cred_link")!!,
            selinuxLink = getLong("offset.selinux_link") ?: getLong("selinux_link")!!,
            userNs = getLong("offset.user_ns") ?: getLong("user_ns")!!,
            rootUser = getLong("offset.root_user") ?: getLong("root_user")!!,
            groups = getLong("offset.groups") ?: getLong("groups")!!,
            ucounts = getLong("offset.ucounts") ?: getLong("ucounts")!!,
            taskCred = (getLong("offset.task_cred") ?: getLong("task_cred")!!).toInt(),
            taskStructSize = (getLong("offset.task_struct_size") ?: getLong("task_struct_size")!!).toInt(),
            credUid = (getLong("offset.cred_uid") ?: getLong("cred_uid")!!).toInt(),
            kitimerNode = (getLong("offset.kitimer_node") ?: getLong("kitimer_node")!!).toInt(),
            kitimerSize = getLong("offset.kitimer_size") ?: getLong("kitimer_size") ?: 0x108L,
            taskAllocLink = getLong("offset.task_alloc_link") ?: getLong("task_alloc_link") ?: 0L,
            credSize = (getLong("offset.cred_size") ?: 184L).toInt(),
            credCapInheritable = (getLong("offset.cred_cap_inheritable") ?: 48L).toInt(),
            credCapPermitted = (getLong("offset.cred_cap_permitted") ?: 56L).toInt(),
            credCapEffective = (getLong("offset.cred_cap_effective") ?: 64L).toInt(),
            credCapBset = (getLong("offset.cred_cap_bset") ?: 72L).toInt(),
            credCapAmbient = (getLong("offset.cred_cap_ambient") ?: 80L).toInt(),
            credSecurity = (getLong("offset.cred_security") ?: 128L).toInt(),
            credUser = (getLong("offset.cred_user") ?: 136L).toInt(),
            credUserNs = (getLong("offset.cred_user_ns") ?: 144L).toInt(),
            credUcounts = (getLong("offset.cred_ucounts") ?: 152L).toInt(),
            credGroups = (getLong("offset.cred_groups") ?: 160L).toInt(),
        )
    }

    /** 渲染为 HOCON 文本。 */
    fun toHocon(): String = HoconSupport.render(fields)

    companion object {
        /**
         * 当前 profile 文档格式版本（schema_version）。
         * 2 = v2 点分路径布局 + 值域校验时代；缺失或低于 2 的用户导入 profile
         * 一律视为旧版生成（可能含 BTF 错位污染偏移），执行门拒绝并要求重导。
         */
        const val SCHEMA_VERSION_CURRENT = 2

        /** 内核符号地址下界（arm64 TTBR1 半空间 0xFFFF 开头）。 */
        private val SYMBOL_ADDRESS_MIN: Long = 0xFFFF000000000000UL.toLong()

        /** 符号地址类字段（link-time 内核 VA 或线性映射差值）。 */
        private val SYMBOL_ADDRESS_FIELDS = listOf(
            "page_offset",
            "offset.timer_link", "offset.cred_link", "offset.selinux_link",
            "offset.user_ns", "offset.root_user", "offset.groups", "offset.ucounts",
        )

        private const val TASK_STRUCT_SIZE_MIN = 0x800L
        private const val TASK_STRUCT_SIZE_MAX = 0x8000L
        private const val CRED_MEMBER_MAX = 0x400L
        private const val CRED_SIZE_MIN = 0x40L
        private const val KITIMER_SIZE_MIN = 0x80L
        private const val KITIMER_SIZE_MAX = 0x400L
        private const val KITIMER_SIZE_DEFAULT = 0x108L

        /** cred 成员偏移类字段（有 Kotlin 默认值兜底，提供时才校验）。 */
        private val CRED_MEMBER_FIELDS = listOf(
            "cred_cap_inheritable", "cred_cap_permitted", "cred_cap_effective",
            "cred_cap_bset", "cred_cap_ambient",
            "cred_security", "cred_user", "cred_user_ns", "cred_ucounts", "cred_groups",
        )

        /** 必填的数值/字符串字段（vuln_type、recommend_shizuku 有默认值，不在此列）。 */
        private val REQUIRED_NUMERIC = listOf(
            "page_offset",
            "offset.timer_link", "offset.cred_link", "offset.selinux_link",
            "offset.user_ns", "offset.root_user", "offset.groups", "offset.ucounts",
            "offset.task_cred", "offset.task_struct_size", "offset.cred_uid", "offset.kitimer_node",
        )

        /** 从 HOCON/JSON 文本解析为 ProfileDocument。 */
        fun parse(text: String, includer: ((String) -> String?)? = null): ProfileDocument {
            val root = HoconSupport.parseObject(text, includer)
            val release = (root["release"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw ProfileConversionException("缺少 release 字段")
            val schemaVersion = coerceLong(root["schema_version"])?.toInt() ?: 0
            return ProfileDocument(release = release, schemaVersion = schemaVersion, fields = root)
        }

        /** 从既有 KernelProfile 反向构造 ProfileDocument（用于导出/编辑）。 */
        fun fromKernelProfile(kp: KernelProfile): ProfileDocument {
            val offset = linkedMapOf<String, Any?>(
                "timer_link" to kp.timerLink,
                "cred_link" to kp.credLink,
                "selinux_link" to kp.selinuxLink,
                "user_ns" to kp.userNs,
                "root_user" to kp.rootUser,
                "groups" to kp.groups,
                "ucounts" to kp.ucounts,
                "task_cred" to kp.taskCred,
                "task_struct_size" to kp.taskStructSize,
                "cred_uid" to kp.credUid,
                "kitimer_node" to kp.kitimerNode,
                "kitimer_size" to kp.kitimerSize,
                "task_alloc_link" to kp.taskAllocLink,
                "cred_size" to kp.credSize,
                "cred_cap_inheritable" to kp.credCapInheritable,
                "cred_cap_permitted" to kp.credCapPermitted,
                "cred_cap_effective" to kp.credCapEffective,
                "cred_cap_bset" to kp.credCapBset,
                "cred_cap_ambient" to kp.credCapAmbient,
                "cred_security" to kp.credSecurity,
                "cred_user" to kp.credUser,
                "cred_user_ns" to kp.credUserNs,
                "cred_ucounts" to kp.credUcounts,
                "cred_groups" to kp.credGroups,
            )
            val fields = linkedMapOf<String, Any?>(
                "release" to kp.release,
                "schema_version" to SCHEMA_VERSION_CURRENT,
                "vuln_type" to kp.vulnType,
                "recommend_shizuku" to if (kp.recommendShizuku) 1 else 0,
                "page_offset" to kp.pageOffset,
                "offset" to offset,
            )
            return ProfileDocument(
                release = kp.release,
                schemaVersion = SCHEMA_VERSION_CURRENT,
                fields = fields,
            )
        }
    }
}

/** profile 转换/校验异常。 */
class ProfileConversionException(message: String) : RuntimeException(message)


/** 将 HOCON/JSON 中可能出现的数值类型（Long/Int/Double/十六进制或十进制字符串）转为 Long?。 */
internal fun coerceLong(value: Any?): Long? {
    return when (value) {
        null -> null
        is Long -> value
        is Int -> value.toLong()
        is Double -> if (value.isFinite()) value.toLong() else null
        is Float -> if (value.isFinite()) value.toLong() else null
        is Boolean -> if (value) 1L else 0L
        is String -> {
            val s = value.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("0x", ignoreCase = true) || s.startsWith("0X")) {
                runCatching { java.lang.Long.parseUnsignedLong(s.removePrefix("0x").removePrefix("0X"), 16) }
                    .getOrNull()
            } else if (s.any { it == '.' || it == 'e' || it == 'E' }) {
                s.toDoubleOrNull()?.toLong()
            } else {
                s.toLongOrNull()
                    ?: runCatching { java.lang.Long.parseUnsignedLong(s) }.getOrNull()
            }
        }
        else -> null
    }
}
