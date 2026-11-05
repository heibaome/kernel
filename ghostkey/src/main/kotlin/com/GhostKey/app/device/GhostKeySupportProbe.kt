package com.GhostKey.app.device

import android.content.Context
import com.GhostKey.app.data.ProfileRepository

/**
 * 无 UI 的内核支持探测：宿主（GhostLock 外壳）在未构造完整 ExploitEngine 前，
 * 用于判断当前 uname -r 是否被 GhostKey 内置/用户 profile 命中。
 *
 * 仅做精确 release 匹配（与 [ProfileRepository.resolve] 同一策略，不按 KMI 前缀
 * 回退——同 KMI 不同构建的内核符号偏移不一致，回退会套用错误链接偏移）。
 */
object GhostKeySupportProbe {

    /** release 被命中时返回其实际 release（用户层优先），否则 null。 */
    fun matchedRelease(context: Context, release: String): String? {
        if (release.isBlank() || release == "unknown") return null
        return runCatching { ProfileRepository(context).resolve(release)?.release }.getOrNull()
    }
}
