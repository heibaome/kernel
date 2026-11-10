package com.ghostlock.app.domain.usecase

import com.ghostlock.app.domain.model.LogEntry
import com.ghostlock.app.domain.model.LogTone

class FormatLogUseCase {
    operator fun invoke(line: String): LogEntry {
        val text = stripAnsi(if (line.endsWith('\n')) line else "$line\n")
        val body = text.replaceFirst(sourceTag, "")
        val marker = body.getOrNull(1).takeIf { body.startsWith('[') && body.getOrNull(2) == ']' }
        val message = body.replace(leadingTags, "").removePrefix("=== ")
        val tagged = text
            .removePrefix("<-> ")
            .removePrefix("<b> ")
            .removePrefix("<s> ")
            .removePrefix("<k> ")
        val tone = when {
            text.startsWith("<->") -> LogTone.Error
            text.startsWith("<b>") ->
                if (tagged.contains("connected") && !tagged.contains("disconnected")) {
                    LogTone.Success
                } else {
                    LogTone.Progress
                }

            text.startsWith("<s>") -> if (looksLikeError(tagged)) LogTone.Error else LogTone.Shizuku
            text.startsWith("<k>") ->
                if (tagged.startsWith("result:")) resultTone(tagged) else LogTone.Kotlin

            tagged.startsWith("result:") -> resultTone(tagged)
            // GhostKey 编排层阶段行：按状态染色，避免全部落 marker '*' 的 Warning 橙
            gkStage.matches(text.trimEnd('\n')) -> gkStageTone(text)
            isWriteRound(message) -> if (marker == '-' || marker == '!') LogTone.Error else LogTone.Progress
            marker == '+' -> LogTone.Success
            marker == '-' || marker == '!' -> LogTone.Error
            marker == '*' -> LogTone.Warning
            body.startsWith("error", ignoreCase = true) -> LogTone.Error
            body.startsWith("warning", ignoreCase = true) -> LogTone.Warning
            // GhostKey native 无标记行：错误/警告关键词染色，其余保持默认灰
            looksLikeError(message) -> LogTone.Error
            message.contains("warn:", ignoreCase = true) -> LogTone.Warning
            else -> LogTone.Default
        }
        return LogEntry(text, tone)
    }

    private fun gkStageTone(line: String): LogTone =
        when {
            line.endsWith(": ok") -> LogTone.Success
            line.endsWith(": failed") -> LogTone.Error
            line.endsWith(": degraded") -> LogTone.Warning
            else -> LogTone.Progress
        }

    private fun isWriteRound(message: String): Boolean = listOf("W1", "W2", "W3", "Write 1").any(message::startsWith)

    private fun resultTone(text: String): LogTone =
        if (listOf("failed", "unsupported", "cancelled").any(text::contains)) LogTone.Error else LogTone.Success

    private fun looksLikeError(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("error") ||
                listOf("fail", "cannot", "unable", "denied", "refus").any(lower::contains)
    }

    private fun stripAnsi(value: String): String = value.replace(ansi, "")

    private companion object {
        val ansi = Regex("\\u001B\\[[;\\d]*m")
        val sourceTag = Regex("^\\[[a-z]+]\\s+")
        val leadingTags = Regex("^(\\[[^]]+]\\s*)+")

        /** GhostKey 编排层阶段行（"[*] stage 1/5 calib_a: running"），格式专用，避免误伤 GhostLock 的 "[*]" 行。 */
        val gkStage = Regex("""^\[\*] stage \d+/\d+ [a-z_]+: \S+$""")
    }
}
