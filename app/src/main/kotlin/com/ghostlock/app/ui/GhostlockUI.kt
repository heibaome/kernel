package com.ghostlock.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostlock.app.BuildConfig
import com.ghostlock.app.R
import com.ghostlock.app.data.ManagerScanner
import com.ghostlock.app.domain.model.CpuPair
import com.ghostlock.app.domain.model.ExecutionFieldValue
import com.ghostlock.app.domain.model.ProfileFieldNode
import com.ghostlock.app.domain.model.ShizukuStatus
import com.ghostlock.app.domain.model.UserProfileFile
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 提权引擎二选一：同一套主 UI、同一执行 sheet，仅攻击载荷不同。
 * 默认值在 ViewModel 刷新快照时按设备支持性计算（GhostLock 优先）。
 */
enum class ExploitEngine { GHOSTLOCK, GHOSTKEY }

data class GhostlockUiState(
    val deviceName: String = "",
    /** 当前选中的提权引擎（默认 = 设备支持的模式）。 */
    val engine: ExploitEngine = ExploitEngine.GHOSTLOCK,
    /** GhostKey (CVE-2026-98260) 内核支持性（IN_RANGE 才可执行）。 */
    val ghostKeySupported: Boolean = false,
    /** GhostKey 内核支持性未知（无法读版本/模拟器），允许手动选择但执行前强提示。 */
    val ghostKeySupportUnknown: Boolean = false,
    /** GhostKey 自动匹配到的 profile release；null 表示需导入 boot.img。 */
    val ghostKeyProfileRelease: String? = null,
    /** GhostKey 已导入的用户 profile（boot.img 生成），供精简列表选择/删除。 */
    val ghostKeyStoredProfiles: List<GhostKeyProfileSummary> = emptyList(),
    /** GhostKey 手动选用的 profile release；null = 自动匹配当前内核。 */
    val ghostKeySelectedRelease: String? = null,
    /** Cloud profile pack source shown on the load-configuration screen. */
    val cloudProfileSource: String = "",
    /** True while a manual cloud profile update is running. */
    val cloudProfileUpdating: Boolean = false,
    val kernelRelease: String = "",
    val socName: String = "",
    val kernelSupported: Boolean = false,
    val shizukuEnabled: Boolean = false,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_REQUIRED,
    val running: Boolean = false,
    val cpuPairLabels: List<String> = emptyList(),
    val cpuPairIndex: Int = 0,
    /** CPU pair from the resolved profile when it differs from the device pick. */
    val customCpuPair: CpuPair? = null,
    val safeModeEnabled: Boolean = false,
    val forceAttackTestEnabled: Boolean = false,
    /** 待展示的 15 秒风险确认对话框；null = 无。 */
    val riskConfirm: RiskConfirmKind? = null,
    /** BUILTIN_PROFILE 确认时暂存的目标 release。 */
    val riskPendingRelease: String? = null,
    val tcpRouteEnabled: Boolean = true,
    val compact: Boolean = false,
    val executionSheetVisible: Boolean = false,
    val executionSheetDismissible: Boolean = false,
    val dialogVisible: Boolean = false,
    val dialogType: DialogType = DialogType.NONE,
    val dialogTitleRes: Int = 0,
    val dialogMessage: String = "",
    val dialogMessageRes: Int = 0,
    /** Format arguments for [dialogMessageRes]; empty means no formatting. */
    val dialogMessageArgs: List<String> = emptyList(),
    val dialogItems: List<String> = emptyList(),
    val dialogItemResIds: List<Int> = emptyList(),
    val dialogCurrentItemIndex: Int = -1,
    val dialogInput: String = "",
    val dialogConfirmLabelRes: Int = R.string.parse_start,
    /** Documentation URL shown as an extra button on a NOTICE dialog. */
    val dialogDocUrl: String? = null,
    val overwriteDialogVisible: Boolean = false,
    val overwriteMessage: String = "",
    val logLines: List<GhostlockLogLine> = emptyList(),
    val executionRelease: String = "",
    val executionHasProfile: Boolean = false,
    val executionFields: List<ExecutionFieldValue> = emptyList(),
    val executionEditing: Map<String, String> = emptyMap(),
    val advancedScreenVisible: Boolean = false,
    val ghostKeyConfigVisible: Boolean = false,
    val debugExportEnabled: Boolean = true,
    val debugExportLocation: String = "",
    val debugKernelLogEnabled: Boolean = true,
    val aboutVisible: Boolean = false,
    val parametersVisible: Boolean = false,
    val profileOverrideVisible: Boolean = false,
    val advancedOverrideVisible: Boolean = false,
    /** Stored document edited by the open session; null for the builtin. */
    val editTargetName: String? = null,
    val profileOverrideRelease: String = "",
    val profileOverrideRoots: List<ProfileFieldNode> = emptyList(),
    val profileOverrideEditing: Map<String, String> = emptyMap(),
    /** Controller-reported geometry violations, dotted paths. */
    val profileInvalidPaths: Set<String> = emptySet(),
    /** Explicit route from the profile; null means geometry inference. */
    val profileRoute: String? = null,
    /** Declared fallback route; null/"none" means disabled. */
    val profileFallback: String? = null,
    /** Manually selected builtin source; null means automatic matching. */
    val activeBuiltinProfile: String? = null,
    val builtinScreenVisible: Boolean = false,
    /** Unfilled reference templates, listed separately on the builtin picker. */
    val builtinTemplates: List<String> = emptyList(),
    /** Builtin releases sorted by similarity to the device kernel. */
    val builtinProfiles: List<String> = emptyList(),
    val loadConfigVisible: Boolean = false,
    /** Verbatim documents in the user profile folder, newest first. */
    val userProfiles: List<UserProfileFile> = emptyList(),
    /** Loaded user document feeding the imported layer; null means none. */
    val activeUserProfile: String? = null,
    /** File name of the open user-profile detail screen, null when closed. */
    val userProfileDetail: String? = null,
    val userProfileRenameTarget: String? = null,
    val userProfileDeleteTarget: String? = null,
    val managerCandidates: List<ManagerScanner.Candidate> = emptyList(),
    val selectedManagerPackage: String? = null,
    val managerPickerVisible: Boolean = false,
)

enum class DialogType { NONE, LIST, INPUT, CONFIRM, NOTICE }

/** 深入功能的风险确认入口（15 秒不可跳过对话框）。 */
enum class RiskConfirmKind { FORCE_ATTACK, ADVANCED_OVERRIDE, BUILTIN_PROFILE }

/** GhostKey 用户 profile 精简条目（boot.img 导入生成），供引擎选择器下的列表用。 */
data class GhostKeyProfileSummary(
    val fileName: String,
    val release: String,
    val complete: Boolean,
)

data class GhostlockLogLine(val text: String, val color: Int)

interface GhostlockActions {
    fun onRun()
    /** 提权引擎切换（GhostLock / GhostKey 二选一）。 */
    fun onEngineSelected(engine: ExploitEngine)
    /** GhostKey 引擎：发起 boot.img profile 导入选择器。 */
    fun onGhostKeyImportBoot()
    /** GhostKey 引擎：选用/清除用户 profile（null = 自动匹配）。 */
    fun onGhostKeyProfileSelected(fileName: String?)
    /** GhostKey 引擎：删除已导入的用户 profile。 */
    fun onGhostKeyProfileDeleted(fileName: String)
    fun onProfileInvalid()
    fun onStatusClick()
    fun onCloseExecutionSheet()
    fun onCopyLogs()
    fun onImportOffsetsHocon()
    fun onImportOffsetsJson()
    fun onDocumentsResult(request: DocumentRequest, uris: List<String>)
    fun onParseOta()
    fun onParseImage()
    fun onCpuPairSelected(index: Int)
    fun onSafeModeChanged(enabled: Boolean)
    fun onForceAttackTestChanged(enabled: Boolean)
    /** 风险确认对话框：用户勾选同意并等满 15 秒后确认。 */
    fun onRiskConfirm()
    /** 风险确认对话框：取消（仅按钮可关，点外部/返回无效）。 */
    fun onRiskDismiss()
    fun onShizukuChanged(enabled: Boolean)
    fun onOpenManagerPicker()
    fun onCloseManagerPicker()
    fun onManagerSelected(pkg: String?)
    fun onDialogItemSelected(index: Int)
    fun onDialogInputChange(value: String)
    fun onDialogConfirm(value: String)
    fun onDialogDismiss()
    fun onDialogDismissFinished()
    fun onOverwriteConfirm()
    fun onOverwriteDismiss()
    fun onExecutionFieldChanged(path: String, value: String)
    fun onRouteChanged(index: Int)
    fun onFallbackChanged(index: Int)
    fun onExportProfile()
    fun onSaveProfileEdits()
    fun onSaveProfileAs()
    fun onExportProfileEdits()
    fun onRevertProfileEdits()
    fun onOpenAdvanced()
    fun onCloseAdvanced()
    fun onOpenGhostKeyConfig()
    fun onCloseGhostKeyConfig()
    fun onShowAbout()
    fun onCloseAbout()
    fun onDebugExportChanged(enabled: Boolean)
    fun onDebugExportLocationPick()
    fun onDebugKernelLogChanged(enabled: Boolean)
    fun onOpenParameters()
    fun onCloseParameters()
    fun onOpenLoadConfig()
    fun onCloseLoadConfig()
    fun onOpenUserProfileDetail(name: String)
    fun onCloseUserProfileDetail()
    fun onLoadUserProfile(name: String)
    fun onUnloadUserProfile()
    fun onEditUserProfile(name: String)
    fun onUserProfileRename(name: String)
    fun onUserProfileExport(name: String)
    fun onConvertUserProfile(name: String)
    fun onUserProfileDelete(name: String)
    fun onUserProfileDeleteConfirm()
    fun onUserProfileDeleteDismiss()
    fun onCheckCloudProfiles()
    fun onEditCloudProfileSource()
    fun onOpenBuiltinProfiles()
    fun onCloseBuiltinProfiles()
    fun onSelectBuiltinProfile(release: String?)
    fun onOpenProfileOverrides()
    fun onCloseProfileOverrides()
    fun onOpenAdvancedOverrides()
    fun onCloseAdvancedOverrides()
    fun onProfileOverrideChanged(path: String, value: String)
}

internal sealed interface GhostlockScreen : NavKey {
    data object Main : GhostlockScreen
    data object Advanced : GhostlockScreen
    data object GhostKeyConfig : GhostlockScreen
    data object About : GhostlockScreen
    data object Parameters : GhostlockScreen
    data object LoadConfig : GhostlockScreen
    data object Builtin : GhostlockScreen
    data class UserProfileDetail(val name: String) : GhostlockScreen
    data object ProfileOverride : GhostlockScreen
    data object AdvancedOverride : GhostlockScreen
}

internal fun navigationPath(state: GhostlockUiState): List<GhostlockScreen> {
    val path = mutableListOf<GhostlockScreen>(GhostlockScreen.Main)
    if (state.ghostKeyConfigVisible) {
        path += GhostlockScreen.GhostKeyConfig
        /* GK 配置页内的「关于」叠加在配置页之上（与 GL 分支同一处理）。 */
        if (state.aboutVisible) path += GhostlockScreen.About
        return path
    }
    if (!state.advancedScreenVisible) return path
    path += GhostlockScreen.Advanced
    if (state.aboutVisible) {
        path += GhostlockScreen.About
        return path
    }
    if (!state.parametersVisible) return path
    path += GhostlockScreen.Parameters
    if (state.loadConfigVisible) {
        path += GhostlockScreen.LoadConfig
        when {
            state.builtinScreenVisible -> path += GhostlockScreen.Builtin
            state.userProfileDetail != null ->
                path += GhostlockScreen.UserProfileDetail(state.userProfileDetail)
        }
    }
    if (state.profileOverrideVisible) {
        path += GhostlockScreen.ProfileOverride
        if (state.advancedOverrideVisible) path += GhostlockScreen.AdvancedOverride
    }
    return path
}

internal fun syncNavigationPath(backStack: NavBackStack, desired: List<GhostlockScreen>) {
    var common = 0
    while (common < backStack.size && common < desired.size &&
        backStack[common] == desired[common]
    ) {
        common++
    }
    while (backStack.size > common) backStack.removeAt(backStack.lastIndex)
    backStack.addAll(desired.drop(common))
}

private fun closeScreen(screen: GhostlockScreen, actions: GhostlockActions) {
    when (screen) {
        GhostlockScreen.Main -> Unit
        GhostlockScreen.Advanced -> actions.onCloseAdvanced()
        GhostlockScreen.GhostKeyConfig -> actions.onCloseGhostKeyConfig()
        GhostlockScreen.About -> actions.onCloseAbout()
        GhostlockScreen.Parameters -> actions.onCloseParameters()
        GhostlockScreen.LoadConfig -> actions.onCloseLoadConfig()
        GhostlockScreen.Builtin -> actions.onCloseBuiltinProfiles()
        is GhostlockScreen.UserProfileDetail -> actions.onCloseUserProfileDetail()
        GhostlockScreen.ProfileOverride -> actions.onCloseProfileOverrides()
        GhostlockScreen.AdvancedOverride -> actions.onCloseAdvancedOverrides()
    }
}

@Composable
internal fun GhostlockApp(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        val desiredPath = navigationPath(state)
        val backStack = remember {
            navBackStackOf(GhostlockScreen.Main).apply { addAll(desiredPath.drop(1)) }
        }
        LaunchedEffect(desiredPath) { syncNavigationPath(backStack, desiredPath) }
        val swipeBack = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
            NavSwipeDirection.RightToLeft
        } else {
            NavSwipeDirection.LeftToRight
        }
        Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.fillMaxSize(),
                    effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                    onBack = {
                        when {
                            state.executionSheetVisible -> {
                                if (state.executionSheetDismissible) actions.onCloseExecutionSheet()
                            }

                            state.overwriteDialogVisible -> actions.onOverwriteDismiss()
                            state.dialogVisible -> actions.onDialogDismiss()
                            state.userProfileDeleteTarget != null -> actions.onUserProfileDeleteDismiss()
                            else -> {
                                val screen = backStack.lastOrNull() as? GhostlockScreen
                                if (screen != null && screen != GhostlockScreen.Main) {
                                    closeScreen(screen, actions)
                                    backStack.removeAt(backStack.lastIndex)
                                }
                            }
                        }
                    },
                ) {
                    entry<GhostlockScreen.Main>(swipeDismiss = NavSwipeDirection.None) {
                        MainScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.Advanced>(swipeDismiss = swipeBack) {
                        AdvancedScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.GhostKeyConfig>(swipeDismiss = swipeBack) {
                        GhostKeyConfigScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.About>(swipeDismiss = swipeBack) {
                        AboutScreen(onBack = actions::onCloseAbout)
                    }
                    entry<GhostlockScreen.Parameters>(swipeDismiss = swipeBack) {
                        ParameterScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.LoadConfig>(swipeDismiss = swipeBack) {
                        LoadConfigScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.Builtin>(swipeDismiss = swipeBack) {
                        BuiltinProfileScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.UserProfileDetail>(swipeDismiss = swipeBack) { screen ->
                        UserProfileDetailScreen(state = state, actions = actions, name = screen.name)
                    }
                    entry<GhostlockScreen.ProfileOverride>(swipeDismiss = swipeBack) {
                        ProfileOverrideScreen(state = state, actions = actions)
                    }
                    entry<GhostlockScreen.AdvancedOverride>(swipeDismiss = swipeBack) {
                        AdvancedOverrideScreen(state = state, actions = actions)
                    }
                }
                GhostlockDialog(state = state, actions = actions)
                RiskConfirmDialog(state = state, actions = actions)
                GhostlockOverwriteDialog(state = state, actions = actions)
                GhostlockExecutionSheet(state = state, actions = actions)
                GhostlockManagerPickerSheet(state = state, actions = actions)
            }
        }
    }
}

@Composable
private fun MainScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = "GhostSuite",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        MainContent(
            state = state,
            actions = actions,
            scrollBehavior = scrollBehavior,
            scaffoldPadding = paddingValues,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun pageContentPadding(
    scaffoldPadding: PaddingValues,
    top: Dp = 8.dp,
    bottom: Dp = 12.dp,
    horizontalMin: Dp = 12.dp,
): PaddingValues {
    val windowWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val horizontal = ((windowWidth - 800.dp) / 2).coerceAtLeast(horizontalMin)
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = scaffoldPadding.calculateTopPadding() + top,
        bottom = scaffoldPadding.calculateBottomPadding() + bottom,
    )
}

@Composable
private fun GhostlockExecutionSheet(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    OverlayBottomSheet(
        show = state.executionSheetVisible,
        title = stringResource(R.string.log_title),
        allowDismiss = state.executionSheetDismissible,
        onDismissRequest = actions::onCloseExecutionSheet,
        startAction = {
            IconButton(onClick = actions::onCopyLogs) {
                Icon(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
        endAction = {
            IconButton(
                enabled = state.executionSheetDismissible,
                onClick = actions::onCloseExecutionSheet,
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.action_close),
                )
            }
        },
        content = {
            LogPanel(
                lines = state.logLines,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 520.dp)
                    .navigationBarsPadding(),
            )
        },
    )
}

@Composable
private fun GhostlockManagerPickerSheet(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    OverlayBottomSheet(
        show = state.managerPickerVisible,
        title = stringResource(R.string.manager_picker_title),
        onDismissRequest = actions::onCloseManagerPicker,
        content = {
            var showAll by remember { mutableStateOf(false) }
            // Auto stays first so a saved selection can always be cleared,
            // even when no manager app was found at all.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .navigationBarsPadding()
                    .selectableGroup(),
            ) {
                item {
                    ManagerPickerRow(
                        title = stringResource(R.string.manager_summary_auto),
                        subtitle = null,
                        icon = null,
                        selected = state.selectedManagerPackage == null,
                    ) { actions.onManagerSelected(null) }
                }
                if (state.managerCandidates.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.manager_picker_empty),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            textAlign = TextAlign.Center,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                } else {
                    val compatible = state.managerCandidates.filter { it.hasKsud }
                    val incompatible = state.managerCandidates.filterNot { it.hasKsud }
                    val visibleIncompatible = if (showAll) incompatible else emptyList()
                    items(compatible, key = { it.packageName }) { candidate ->
                        ManagerPickerRow(
                            title = candidate.label,
                            subtitle = candidate.packageName,
                            icon = candidate,
                            selected = state.selectedManagerPackage == candidate.packageName,
                        ) { actions.onManagerSelected(candidate.packageName) }
                    }
                    items(visibleIncompatible, key = { it.packageName }) { candidate ->
                        ManagerPickerRow(
                            title = candidate.label,
                            subtitle = stringResource(R.string.manager_no_ksud),
                            icon = candidate,
                            selected = state.selectedManagerPackage == candidate.packageName,
                            muted = true,
                        ) { actions.onManagerSelected(candidate.packageName) }
                    }
                    if (incompatible.isNotEmpty()) {
                        item {
                            TextButton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp),
                                text = stringResource(
                                    if (showAll) R.string.manager_hide_all else R.string.manager_show_all,
                                ),
                                onClick = { showAll = !showAll },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ManagerPickerRow(
    title: String,
    subtitle: String?,
    icon: ManagerScanner.Candidate?,
    selected: Boolean,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val imageBitmap = remember(icon) {
        icon?.let { candidate ->
            runCatching {
                val drawable = pm.getApplicationIcon(candidate.packageName)
                val width = maxOf(drawable.intrinsicWidth, 1)
                val height = maxOf(drawable.intrinsicHeight, 1)
                android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(android.graphics.Canvas(bitmap))
                }
            }.getOrNull()
        }?.asImageBitmap()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 0.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (muted) MiuixTheme.colorScheme.onBackgroundVariant else MiuixTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.CheckCircleOutline,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun GhostlockDialog(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    OverlayDialog(
        show = state.dialogVisible,
        title = if (state.dialogType == DialogType.NONE) null else stringResource(state.dialogTitleRes),
        onDismissRequest = actions::onDialogDismiss,
        onDismissFinished = actions::onDialogDismissFinished,
        content = {
            when (state.dialogType) {
                DialogType.LIST -> {
                    val items = state.dialogItems.ifEmpty { state.dialogItemResIds.map { stringResource(it) } }
                    items.forEachIndexed { index, item ->
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            text = if (index == state.dialogCurrentItemIndex) {
                                stringResource(R.string.export_current_marker, item)
                            } else {
                                item
                            },
                            onClick = { actions.onDialogItemSelected(index) },
                        )
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.cancel),
                        onClick = actions::onDialogDismiss,
                    )
                }

                DialogType.INPUT -> {
                    TextField(
                        value = state.dialogInput,
                        onValueChange = actions::onDialogInputChange,
                        label = stringResource(state.dialogMessageRes),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.cancel),
                            onClick = actions::onDialogDismiss,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(state.dialogConfirmLabelRes),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { actions.onDialogConfirm(state.dialogInput) },
                        )
                    }
                }

                DialogType.CONFIRM -> {
                    Text(
                        text = stringResource(state.dialogMessageRes),
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.cancel),
                            onClick = actions::onDialogDismiss,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.w3_shizuku_hint_enable),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { actions.onDialogConfirm("") },
                        )
                    }
                }

                DialogType.NOTICE -> {
                    Text(
                        text = if (state.dialogMessageArgs.isEmpty()) {
                            stringResource(state.dialogMessageRes)
                        } else {
                            stringResource(
                                state.dialogMessageRes,
                                *state.dialogMessageArgs.toTypedArray(),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        state.dialogDocUrl?.let { docUrl ->
                            val uriHandler = LocalUriHandler.current
                            TextButton(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.dialog_open_guide),
                                onClick = { uriHandler.openUri(docUrl) },
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        TextButton(
                            modifier = Modifier.weight(1f),
                            text = stringResource(R.string.dialog_dismiss),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = actions::onDialogDismiss,
                        )
                    }
                }

                DialogType.NONE -> Unit
            }
        },
    )
}

@Composable
private fun GhostlockOverwriteDialog(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    OverlayDialog(
        show = state.overwriteDialogVisible,
        title = stringResource(R.string.overwrite_title),
        summary = stringResource(R.string.overwrite_message, state.overwriteMessage),
        onDismissRequest = actions::onOverwriteDismiss,
        content = {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.cancel),
                    onClick = actions::onOverwriteDismiss,
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.overwrite_yes),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = actions::onOverwriteConfirm,
                )
            }
        },
    )
}

@Composable
private fun MainContent(
    state: GhostlockUiState,
    actions: GhostlockActions,
    scrollBehavior: ScrollBehavior,
    scaffoldPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .scrollEndHaptic()
            .overScrollVertical()
            .scrollEndHaptic()
            .fillMaxHeight()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding(),
        contentPadding = pageContentPadding(scaffoldPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "controls") {
            ControlPanel(
                state = state,
                actions = actions,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "run") {
            RunButton(
                running = state.running,
                supported = state.kernelSupported &&
                        state.executionHasProfile &&
                        (!state.shizukuEnabled ||
                                state.shizukuStatus == ShizukuStatus.READY),
                profileValid = state.profileInvalidPaths.isEmpty(),
                labelRes = R.string.action_run,
                onClick = actions::onRun,
                onBlockedClick = actions::onProfileInvalid,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ControlPanel(
    state: GhostlockUiState,
    actions: GhostlockActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val ghostKeyEngine = state.engine == ExploitEngine.GHOSTKEY
        ActivationStatusCard(
            supported = if (ghostKeyEngine) state.ghostKeySupported else state.kernelSupported,
            profileAvailable = if (ghostKeyEngine) {
                state.ghostKeyProfileRelease != null
            } else {
                state.executionHasProfile
            },
            profileValid = state.profileInvalidPaths.isEmpty(),
            shizukuEnabled = state.shizukuEnabled,
            shizukuStatus = state.shizukuStatus,
            ghostKey = ghostKeyEngine,
            onParametersClick = if (ghostKeyEngine) {
                actions::onGhostKeyImportBoot
            } else {
                actions::onOpenParameters
            },
            onShizukuClick = actions::onStatusClick,
            modifier = Modifier.fillMaxWidth(),
        )
        EngineCard(
            engine = state.engine,
            ghostKeySupported = state.ghostKeySupported,
            ghostKeySupportUnknown = state.ghostKeySupportUnknown,
            onEngineSelected = actions::onEngineSelected,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        DeviceInfoCard(
            deviceName = state.deviceName,
            socName = state.socName,
            kernelRelease = state.kernelRelease,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        Card(modifier = modifier.padding(top = 12.dp)) {
            val chosenLabel = state.managerCandidates
                .firstOrNull { it.packageName == state.selectedManagerPackage }
                ?.label
                ?: state.selectedManagerPackage
            ArrowPreference(
                title = stringResource(R.string.manager_label),
                summary = chosenLabel ?: stringResource(R.string.manager_summary_auto),
                onClick = actions::onOpenManagerPicker,
            )
            if (state.cpuPairLabels.isNotEmpty()) {
                val customPair = state.customCpuPair
                val customSummary = customPair?.let {
                    stringResource(
                        R.string.cpu_pair_custom,
                        "${it.primary}, ${it.consumer}",
                    )
                }
                OverlaySpinnerPreference(
                    title = stringResource(R.string.cpu_pair_label),
                    items = state.cpuPairLabels.map { DropdownItem(icon = null, title = it) },
                    selectedIndex = if (customPair == null) state.cpuPairIndex else -1,
                    summary = customSummary,
                    showValue = customPair == null,
                    onSelectedIndexChange = actions::onCpuPairSelected,
                )
            }
            SwitchPreference(
                checked = state.safeModeEnabled,
                onCheckedChange = actions::onSafeModeChanged,
                title = stringResource(R.string.safe_mode_label),
                summary = stringResource(R.string.safe_mode_summary),
            )
            /* PROFILE-SUGGEST-01: the profile suggestion seeds the toggle but no
             * longer hides it; an explicit user choice overrides either way. */
            SwitchPreference(
                checked = state.shizukuEnabled,
                onCheckedChange = actions::onShizukuChanged,
                title = stringResource(R.string.shizuku_label),
                summary = stringResource(
                    when {
                        !state.shizukuEnabled -> R.string.shizuku_summary
                        state.shizukuStatus == ShizukuStatus.READY -> R.string.shizuku_status_ready
                        state.shizukuStatus == ShizukuStatus.PERMISSION_REQUIRED ->
                            R.string.shizuku_status_permission_required

                        state.shizukuStatus == ShizukuStatus.NOT_RUNNING ->
                            R.string.shizuku_status_not_running

                        else -> R.string.shizuku_status_checking
                    },
                ),
            )
        }
        /* 底部配置入口：按引擎进入各自的配置页（debug/关于在配置页内，两页共用）。 */
        Card(modifier = modifier.padding(top = 12.dp)) {
            ArrowPreference(
                title = stringResource(R.string.config_entry),
                summary = stringResource(
                    if (ghostKeyEngine) R.string.config_entry_summary_gk
                    else R.string.config_entry_summary_gl,
                ),
                onClick = if (ghostKeyEngine) actions::onOpenGhostKeyConfig else actions::onOpenAdvanced,
            )
        }
    }
}

/** 调试导出卡：GL 展示导出开关；GK 无公共存储导出能力（运行日志在私有 run_logs/），展示说明卡。 */
@Composable
internal fun DebugExportCard(
    state: GhostlockUiState,
    actions: GhostlockActions,
    ghostKey: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (ghostKey) {
        Card(modifier = modifier) {
            Column {
                Text(
                    text = stringResource(R.string.gk_debug_card_title),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MiuixTheme.textStyles.footnote1,
                )
                Text(
                    text = stringResource(R.string.gk_debug_card_body),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        return
    }
    Card(modifier = modifier) {
        Column {
            SwitchPreference(
                checked = state.debugExportEnabled,
                onCheckedChange = actions::onDebugExportChanged,
                title = stringResource(R.string.debug_export_log),
                summary = stringResource(R.string.debug_export_log_summary),
            )
            AnimatedVisibility(
                visible = state.debugExportEnabled,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.debug_export_location),
                        summary = state.debugExportLocation,
                        onClick = actions::onDebugExportLocationPick,
                    )
                    SwitchPreference(
                        checked = state.debugKernelLogEnabled,
                        onCheckedChange = actions::onDebugKernelLogChanged,
                        title = stringResource(R.string.debug_kernel_log),
                        summary = stringResource(R.string.debug_kernel_log_summary),
                    )
                }
            }
            if (!BuildConfig.DEBUG) {
                Text(
                    text = stringResource(R.string.debug_build_hint),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 关于入口卡：GL/GK 配置页共用。 */
@Composable
internal fun AboutEntryCard(
    actions: GhostlockActions,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        ArrowPreference(
            title = stringResource(R.string.about),
            summary = stringResource(R.string.about_summary),
            onClick = actions::onShowAbout,
        )
    }
}

/**
 * 提权引擎二选一卡：GhostLock (CVE-2026-43499) / GhostKey (CVE-2026-98260)。
 * GhostKey 行右侧显示内核支持状态；不支持的引擎选项禁用。
 */
@Composable
private fun EngineCard(
    engine: ExploitEngine,
    ghostKeySupported: Boolean,
    ghostKeySupportUnknown: Boolean,
    onEngineSelected: (ExploitEngine) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Text(
            text = stringResource(R.string.engine_label),
            fontSize = 17.sp,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
        )
        EngineOption(
            title = stringResource(R.string.engine_ghostlock),
            summary = stringResource(R.string.engine_ghostlock_summary),
            enabled = true,
            supportBadge = null,
            selected = engine == ExploitEngine.GHOSTLOCK,
            onClick = { onEngineSelected(ExploitEngine.GHOSTLOCK) },
        )
        EngineOption(
            title = stringResource(R.string.engine_ghostkey),
            summary = stringResource(R.string.engine_ghostkey_summary),
            enabled = ghostKeySupported || ghostKeySupportUnknown,
            supportBadge = stringResource(
                when {
                    ghostKeySupported -> R.string.engine_support_in_range
                    ghostKeySupportUnknown -> R.string.engine_support_unknown
                    else -> R.string.engine_support_out_of_range
                },
            ),
            selected = engine == ExploitEngine.GHOSTKEY,
            onClick = { onEngineSelected(ExploitEngine.GHOSTKEY) },
        )
    }
}

@Composable
private fun EngineOption(
    title: String,
    summary: String,
    enabled: Boolean,
    supportBadge: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
            )
            Text(
                text = summary,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (supportBadge != null) {
                Text(
                    text = supportBadge,
                    fontSize = 12.sp,
                    color = if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircleOutline,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * GhostKey 引擎下的精简 profile 区：自动匹配状态 + boot.img 导入 +
 * 已存 profile 选用/删除（主要功能提取，不做高级编辑）。
 */
@Composable
private fun GhostKeyProfileCard(
    state: GhostlockUiState,
    onImportBoot: () -> Unit,
    onProfileSelected: (String?) -> Unit,
    onProfileDeleted: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = buildList {
        add(DropdownItem(icon = null, title = stringResource(R.string.ghostkey_profile_auto)))
        state.ghostKeyStoredProfiles.forEach { p ->
            add(DropdownItem(icon = null, title = "${p.fileName} (${p.release})"))
        }
    }
    val selectedFile = state.ghostKeyStoredProfiles.firstOrNull {
        it.release == state.ghostKeySelectedRelease
    }
    val selectedIndex = if (selectedFile == null) 0 else 1 +
        state.ghostKeyStoredProfiles.indexOf(selectedFile)
    /* 与 GL 配置页一致：每个条目独立成卡，而非一张大卡混排。 */
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            OverlaySpinnerPreference(
                title = stringResource(R.string.ghostkey_profile_label),
                summary = state.ghostKeyProfileRelease?.let {
                    stringResource(R.string.ghostkey_profile_matched, it)
                } ?: stringResource(R.string.ghostkey_profile_missing),
                items = items,
                selectedIndex = selectedIndex,
                showValue = false,
                onSelectedIndexChange = { index ->
                    onProfileSelected(
                        if (index <= 0) null else state.ghostKeyStoredProfiles.getOrNull(index - 1)?.fileName,
                    )
                },
            )
        }
        Card {
            ArrowPreference(
                title = stringResource(R.string.ghostkey_import_boot),
                summary = stringResource(R.string.ghostkey_import_boot_summary),
                onClick = onImportBoot,
            )
        }
        if (selectedFile != null) {
            Card {
                ArrowPreference(
                    title = stringResource(R.string.ghostkey_delete_profile, selectedFile.fileName),
                    summary = null,
                    onClick = { onProfileDeleted(selectedFile.fileName) },
                )
            }
        }
    }
}

/** GhostKey 配置页：boot.img 导入与 profile 管理（首页「前往配置」进入）。 */
@Composable
internal fun GhostKeyConfigScreen(
    state: GhostlockUiState,
    actions: GhostlockActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.ghostkey_config_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = actions::onCloseGhostKeyConfig) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "profile") {
                GhostKeyProfileCard(
                    state = state,
                    onImportBoot = actions::onGhostKeyImportBoot,
                    onProfileSelected = actions::onGhostKeyProfileSelected,
                    onProfileDeleted = actions::onGhostKeyProfileDeleted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "debug") {
                DebugExportCard(state = state, actions = actions, ghostKey = true, modifier = Modifier.fillMaxWidth())
            }
            item(key = "about") {
                AboutEntryCard(actions = actions, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ActivationStatusCard(
    supported: Boolean,
    profileAvailable: Boolean,
    profileValid: Boolean,
    shizukuEnabled: Boolean,
    shizukuStatus: ShizukuStatus,
    ghostKey: Boolean = false,
    onParametersClick: () -> Unit,
    onShizukuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accessReady = !shizukuEnabled || shizukuStatus == ShizukuStatus.READY
    val ready = supported && profileAvailable && profileValid && accessReady
    val missing = !supported || !profileAvailable
    val (backgroundColor, title, icon) = when {
        ready -> Triple(
            when {
                MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
                isSystemInDarkTheme() -> Color(0xFF1A3825)
                else -> Color(0xFFDFFAE4)
            },
            R.string.kernel_supported,
            Icons.Rounded.CheckCircleOutline,
        )

        missing -> Triple(
            MiuixTheme.colorScheme.errorContainer,
            if (!supported) {
                R.string.kernel_unsupported
            } else if (ghostKey) {
                R.string.gk_card_need_boot
            } else {
                R.string.kernel_profile_required
            },
            Icons.Rounded.RemoveCircleOutline,
        )

        else -> Triple(
            when {
                MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.tertiaryContainer
                isSystemInDarkTheme() -> Color(0xFF3E2F1B)
                else -> Color(0xFFFFF0DB)
            },
            if (!profileValid) R.string.kernel_profile_invalid else R.string.shizuku_label,
            Icons.Rounded.ErrorOutline,
        )
    }
    val summary = when {
        ready && ghostKey -> R.string.gk_card_ready_summary
        ready && shizukuEnabled -> R.string.kernel_profile_ready_shizuku
        ready -> R.string.kernel_profile_ready
        !supported -> R.string.kernel_unsupported_summary
        !profileAvailable && ghostKey -> R.string.gk_card_need_boot_summary
        !profileAvailable -> R.string.kernel_profile_required_summary
        !profileValid -> R.string.kernel_profile_invalid_summary
        shizukuStatus == ShizukuStatus.PERMISSION_REQUIRED ->
            R.string.shizuku_status_permission_required

        else -> R.string.shizuku_status_not_running
    }
    val action = if (supported && profileAvailable && profileValid && !accessReady) {
        onShizukuClick
    } else {
        onParametersClick
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = backgroundColor),
        onClick = action,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 110.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (MiuixTheme.isDynamicColor) {
                    when (icon) {
                        Icons.Rounded.CheckCircleOutline -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                        Icons.Rounded.RemoveCircleOutline ->
                            MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)

                        else -> MiuixTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    }
                } else {
                    when (icon) {
                        Icons.Rounded.CheckCircleOutline -> Color(0xFF36D167)
                        Icons.Rounded.RemoveCircleOutline -> Color(0xFFF5A623)
                        else -> Color(0xFFF72727)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 27.dp, y = 31.dp)
                    .size(110.dp),
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 120.dp, bottom = 14.dp),
            ) {
                Text(
                    text = stringResource(title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = stringResource(summary),
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(
    deviceName: String,
    socName: String,
    kernelRelease: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DeviceInfoItem(
                    title = stringResource(R.string.device_label),
                    value = deviceName,
                )
                DeviceInfoItem(
                    title = stringResource(R.string.soc_label),
                    value = socName,
                )
                DeviceInfoItem(
                    title = stringResource(R.string.kernel_label),
                    value = kernelRelease,
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoItem(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
    }
}

/* profile-ui: resolved execution view with auto-saved sparse overrides. */
@Composable
internal fun ExecutionEditor(
    state: GhostlockUiState,
    actions: GhostlockActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (field in state.executionFields) {
            val text = state.executionEditing[field.path] ?: field.value.toString()
            val invalid = isFieldInputInvalid(text) || field.path in state.profileInvalidPaths
            TextField(
                value = text,
                onValueChange = { value -> actions.onExecutionFieldChanged(field.path, value) },
                label = fieldLabel(field.path, field.path.substringAfterLast('.')),
                colors = when {
                    invalid ->
                        TextFieldDefaults.textFieldColors(labelColor = FieldErrorHighlight)

                    field.overridden ->
                        TextFieldDefaults.textFieldColors(labelColor = OverrideHighlight)

                    else -> TextFieldDefaults.textFieldColors()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

internal val OverrideHighlight = Color(0xFFF5A623)

/** Red marks an unfilled or non-numeric field in the parameter editors. */
internal val FieldErrorHighlight = Color(0xFFE53935)

internal fun isFieldInputInvalid(text: String): Boolean = text.trim().toLongOrNull() == null

@Composable
private fun RunButton(
    running: Boolean,
    supported: Boolean,
    profileValid: Boolean,
    labelRes: Int,
    onClick: () -> Unit,
    onBlockedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        TextButton(
            text = stringResource(if (running) R.string.action_running else labelRes),
            enabled = supported && profileValid && !running,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
        /* A disabled TextButton consumes no pointer input, so this overlay
         * explains why the run is blocked. */
        if (!running && (!supported || !profileValid)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { onBlockedClick() },
            )
        }
    }
}

@Composable
private fun LogPanel(
    lines: List<GhostlockLogLine>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1220))
            .padding(12.dp),
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line.text.trimEnd('\r', '\n'),
                        color = lineColor(line.color),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

private fun lineColor(color: Int): Color = when (color) {
    0xFFFF6B6B.toInt() -> Color(0xFFFF6B6B)
    0xFF5FD68A.toInt() -> Color(0xFF5FD68A)
    0xFFFFC94D.toInt() -> Color(0xFFFFC94D)
    0xFF60A5FA.toInt() -> Color(0xFF60A5FA)
    else -> Color(0xFFD1D5DB)
}
