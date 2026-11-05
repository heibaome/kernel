package com.ghostlock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlock.app.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 15 秒不可跳过的风险确认：倒计时走完 + 勾选同意后才能确认；
 * 点外部与返回键无效（onDismissRequest 为空），只能通过按钮取消或确认。
 * 组件与配色沿用应用现有对话框体系（OverlayDialog + TextButton 行 + 原生 Checkbox）。
 */
@Composable
fun RiskConfirmDialog(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val kind = state.riskConfirm ?: return
    var secondsLeft by remember(kind) { mutableIntStateOf(RISK_CONFIRM_SECONDS) }
    var agreed by remember(kind) { mutableStateOf(false) }

    LaunchedEffect(kind) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
    }

    OverlayDialog(
        show = true,
        title = stringResource(R.string.risk_confirm_title),
        /* 功能针对性风险说明走 summary 槽位，与现有对话框层次一致。 */
        summary = when (kind) {
            RiskConfirmKind.FORCE_ATTACK -> stringResource(R.string.risk_body_force)
            RiskConfirmKind.ADVANCED_OVERRIDE -> stringResource(R.string.risk_body_override)
            RiskConfirmKind.BUILTIN_PROFILE -> stringResource(
                R.string.risk_body_builtin,
                state.riskPendingRelease ?: "?",
            )
        },
        /* 不可跳过：点外部/返回不关闭，仅按钮可退出。 */
        onDismissRequest = {},
        content = {
            Column {
                Text(
                    text = stringResource(R.string.risk_confirm_disclaimer),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.size(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    showIndication = true,
                    onClick = { agreed = !agreed },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(
                            checked = agreed,
                            onCheckedChange = { agreed = it },
                        )
                        Text(
                            text = stringResource(R.string.risk_confirm_agree),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.cancel),
                        onClick = actions::onRiskDismiss,
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        modifier = Modifier.weight(1f),
                        enabled = agreed && secondsLeft == 0,
                        text = if (secondsLeft > 0) {
                            stringResource(R.string.risk_confirm_wait, secondsLeft)
                        } else {
                            stringResource(R.string.risk_confirm_confirm)
                        },
                        colors = if (agreed && secondsLeft == 0) {
                            ButtonDefaults.textButtonColorsPrimary()
                        } else {
                            ButtonDefaults.textButtonColors()
                        },
                        onClick = actions::onRiskConfirm,
                    )
                }
            }
        },
    )
}

private const val RISK_CONFIRM_SECONDS = 15
