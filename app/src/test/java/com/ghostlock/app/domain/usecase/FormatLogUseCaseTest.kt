package com.ghostlock.app.domain.usecase

import com.ghostlock.app.domain.model.LogTone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FormatLogUseCase 的 GhostKey 行染色规则：
 * 阶段行按状态染色；native 无标记行按错误/警告关键词染色；
 * GhostLock 原有标记行（<s>/<b>/<->/<k>/[*]）行为不回归。
 */
class FormatLogUseCaseTest {

    private val format = FormatLogUseCase()

    private fun toneOf(line: String): LogTone = format(line).tone

    @Test
    fun `gk stage line tone follows status`() {
        assertEquals(LogTone.Progress, toneOf("[*] stage 1/5 calib_a: running"))
        assertEquals(LogTone.Success, toneOf("[*] stage 1/5 calib_a: ok"))
        assertEquals(LogTone.Error, toneOf("[*] stage 3/5 inject: failed"))
        assertEquals(LogTone.Warning, toneOf("[*] stage 2/5 calib_b: degraded"))
        assertEquals(LogTone.Progress, toneOf("[*] stage 5/5 ksu: skipped"))
    }

    @Test
    fun `ghostlock marker star line still warns`() {
        // 不匹配 gkStage（无 "stage n/5" 段），走原有 marker '*' 规则
        assertEquals(LogTone.Warning, toneOf("[*] kick rss\n"))
    }

    @Test
    fun `gk unmarked native error lines turn red`() {
        assertEquals(LogTone.Error, toneOf("[task] leak failed\n"))
        assertEquals(LogTone.Error, toneOf("[config] error: cannot write /x\n"))
        assertEquals(LogTone.Error, toneOf("[uaf] attempt 3: unable to reclaim\n"))
    }

    @Test
    fun `gk unmarked native warn lines turn yellow`() {
        assertEquals(LogTone.Warning, toneOf("[child-cred] warn: mlockall errno=12\n"))
        assertEquals(LogTone.Warning, toneOf("[config] warn: buffer_size_kb not writable\n"))
    }

    @Test
    fun `gk plain native lines stay default`() {
        assertEquals(LogTone.Default, toneOf("[calib_a] collecting call sites (probe 2048)\n"))
        assertEquals(LogTone.Default, toneOf("[config] loaded kernel table: 6.1.157\n"))
        assertEquals(LogTone.Default, toneOf("[uaf] captured=2048 chosen=0xffffff8a6e39f398 raw=258048\n"))
    }

    @Test
    fun `ghostlock tagged lines keep original tones`() {
        assertEquals(LogTone.Shizuku, toneOf("<s> checking Shizuku status...\n"))
        assertEquals(LogTone.Error, toneOf("<s> error: UserService 绑定超时\n"))
        assertEquals(LogTone.Progress, toneOf("<b> kernel release: 6.1.157\n"))
        assertEquals(LogTone.Error, toneOf("<-> 内核支持性校验未通过\n"))
        assertEquals(LogTone.Success, toneOf("result: exploit completed\n"))
    }
}
