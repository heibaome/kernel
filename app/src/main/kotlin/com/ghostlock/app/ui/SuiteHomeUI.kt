package com.ghostlock.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlock.app.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * GhostLock Suite 外壳首页：以 GhostLock 的 Miuix 设计语言为唯一视觉基准，
 * 把 GhostLock 与 GhostKey 两个提权模块收进同一个统一入口。
 *
 * 视觉规则与 [GhostlockUI] 的 ActivationStatusCard 对齐：
 * 大圆角 Card + 大标题 + 副标题 + PressFeedbackType.Tilt 按压反馈；
 * 每个模块卡右侧配一个单字母（G）徽标块代替图标，避免引入额外图标依赖。
 */
@Composable
internal fun SuiteHomeUI(
    ghostLockRunning: Boolean,
    onOpenGhostLock: () -> Unit,
    onOpenGhostKey: () -> Unit,
    onExit: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = stringResource(R.string.suite_home_title),
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = paddingValues.calculateTopPadding() + 8.dp,
                        bottom = paddingValues.calculateBottomPadding() + 12.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModuleCard(
                    title = stringResource(R.string.module_ghostlock_title),
                    summary = if (ghostLockRunning) {
                        stringResource(R.string.module_running_summary)
                    } else {
                        stringResource(R.string.module_ghostlock_summary)
                    },
                    badge = "GL",
                    badgeBackground = if (isSystemInDarkTheme()) Color(0xFF1A2540) else Color(0xFFE3ECFB),
                    badgeContent = if (isSystemInDarkTheme()) Color(0xFF6E96F0) else Color(0xFF2563EB),
                    onClick = onOpenGhostLock,
                )
                ModuleCard(
                    title = stringResource(R.string.module_ghostkey_title),
                    summary = stringResource(R.string.module_ghostkey_summary),
                    badge = "GK",
                    badgeBackground = if (isSystemInDarkTheme()) Color(0xFF123A2C) else Color(0xFFDFFAE4),
                    badgeContent = if (isSystemInDarkTheme()) Color(0xFF4DD8C0) else Color(0xFF0F7A6B),
                    enabled = !ghostLockRunning,
                    disabledHint = stringResource(R.string.module_busy_other_running),
                    onClick = onOpenGhostKey,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.suite_home_footer),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ModuleCard(
    title: String,
    summary: String,
    badge: String,
    badgeBackground: Color,
    badgeContent: Color,
    enabled: Boolean = true,
    disabledHint: String = "",
    onClick: () -> Unit,
) {
    // Miuix Card 无 enabled 参数（0.9.4 源码已核）：onClick 传 null 即无输入事件，
    // 叠加压灰背景/无按压反馈实现禁用态。
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (enabled) {
                MiuixTheme.colorScheme.surface
            } else {
                MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            },
        ),
        onClick = if (enabled) onClick else null,
        showIndication = enabled,
        pressFeedbackType = if (enabled) PressFeedbackType.Tilt else PressFeedbackType.None,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = summary,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                if (!enabled && disabledHint.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = disabledHint,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.8f),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(badgeBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = badgeContent,
                )
            }
        }
    }
}
