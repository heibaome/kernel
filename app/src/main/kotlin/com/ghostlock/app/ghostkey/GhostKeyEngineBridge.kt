package com.ghostlock.app.ghostkey

import android.content.Context
import android.net.Uri
import com.GhostKey.app.data.ImportOutcome
import com.GhostKey.app.data.RunLogMeta
import com.GhostKey.app.data.RunLogStore
import com.GhostKey.app.data.RunResult
import com.GhostKey.app.data.StoredProfile
import com.GhostKey.app.data.ota.BootImageParser
import com.GhostKey.app.device.DeviceBinding
import com.GhostKey.app.engine.ExploitEngine
import com.GhostKey.app.model.ShizukuStatus as GhostKeyShizukuStatus
import com.GhostKey.app.native.NativeContract
import com.GhostKey.app.shizuku.ShizukuPermissionManager
import com.ghostlock.app.domain.model.ShizukuStatus

import java.io.InputStream

/**
 * GhostKey (CVE-2026-98260) 引擎桥接层。
 *
 * 主 UI（GhostLock）与引擎解耦的关键：本类把 GhostKey 引擎的
 * 执行 / Shizuku 授权 / profile 库（boot.img 导入）包装成 GhostLock
 * 侧可直接消费的形态。
 *
 * 日志协议与 GhostLock 同源（<s>/<b>/<->/<k> 标记），onLog 直通
 * GhostlockViewModel.appendLog 即可获得一致着色；阶段进度转译为
 * "[*] stage n/5" 日志行，执行 sheet 无需任何改动。
 */
class GhostKeyEngineBridge(context: Context) {

    private val appContext = context.applicationContext
    private val engine = ExploitEngine(appContext)
    private val shizuku = ShizukuPermissionManager(appContext)
    private val runLogStore = RunLogStore(appContext)

    private var currentSession: RunLogStore.Session? = null

    // ---- 支持性检测（引擎选择的默认依据） ----

    fun kernelRelease(): String = engine.getKernelRelease()

    fun support(): DeviceBinding.KernelSupport = DeviceBinding.kernelSupportOf(kernelRelease())

    fun supported(): Boolean = support() == DeviceBinding.KernelSupport.IN_RANGE

    fun rangeDescription(): String = DeviceBinding.rangeDescription()

    fun deviceDescribe(): String = DeviceBinding.describe()

    // ---- Shizuku（与外壳 ShizukuExploitRunner 的 4815 请求码错开，见模块内 9826） ----

    /** GhostKey 侧 Shizuku 状态映射到 GhostLock 状态卡枚举。 */
    fun shizukuStatus(): ShizukuStatus = when (shizuku.status()) {
        GhostKeyShizukuStatus.NOT_INSTALLED -> ShizukuStatus.NOT_RUNNING
        GhostKeyShizukuStatus.NOT_RUNNING -> ShizukuStatus.NOT_RUNNING
        GhostKeyShizukuStatus.PERMISSION_REQUIRED -> ShizukuStatus.PERMISSION_REQUIRED
        GhostKeyShizukuStatus.READY -> ShizukuStatus.READY
    }

    /** Shizuku 侧 uid 标签（shell/root），状态卡详情用。 */
    fun shizukuUidLabel(): String = shizuku.uidLabel()

    fun addShizukuListener(listener: (ShizukuStatus) -> Unit) {
        shizuku.addListener { status ->
            listener(
                when (status) {
                    GhostKeyShizukuStatus.NOT_INSTALLED -> ShizukuStatus.NOT_RUNNING
                    GhostKeyShizukuStatus.NOT_RUNNING -> ShizukuStatus.NOT_RUNNING
                    GhostKeyShizukuStatus.PERMISSION_REQUIRED -> ShizukuStatus.PERMISSION_REQUIRED
                    GhostKeyShizukuStatus.READY -> ShizukuStatus.READY
                }
            )
        }
    }

    /** 发起授权（9826 请求码）。返回 false 表示 Shizuku 未运行。 */
    fun requestShizukuPermission(): Boolean = shizuku.request()

    // ---- Profile 库（boot.img 导入体系，独立于外壳 HOCON/JSON 体系） ----

    fun matchedRelease(): String? = engine.profiles.resolve(kernelRelease())?.release

    fun knownReleases(): List<String> = engine.profiles.knownReleases()

    fun storedProfiles(): List<StoredProfile> = engine.profiles.storedProfiles()

    fun deleteStored(fileName: String): Boolean = engine.profiles.deleteStored(fileName)

    fun importImg(displayName: String, bytes: ByteArray): ImportOutcome =
        engine.profiles.importer.importImg(displayName, bytes)

    fun importText(displayName: String, text: String): ImportOutcome =
        engine.profiles.importer.importProfile(displayName, text)

    /**
     * 从 SAF Uri 读字节后导入 boot.img。
     *
     * Android boot v3/v4 只读 kernel 段（页对齐偏移起、kernelSize 字节），
     * 不把 ramdisk/dtb 读进内存——完整 boot.img 动辄数百 MB，整文件
     * readBytes 会直接 OOM；raw arm64 Image / gzip 才走全量（此类文件
     * 不含 ramdisk，本身不大）。文件流读取失败时返回携带底层异常的
     * Err，让结果弹窗能区分「读取失败」与「解析失败」。
     */
    fun importImgFromUri(uri: Uri, displayName: String): ImportOutcome? {
        val bytes = try {
            readImageForParse(uri)
        } catch (e: OutOfMemoryError) {
            return ImportOutcome.Err("无法读取所选文件（内存不足，文件可能过大）")
        } catch (e: Exception) {
            return ImportOutcome.Err("无法读取所选文件（${e.javaClass.simpleName}）: ${e.message}")
        } ?: return ImportOutcome.Err("无法读取所选文件（内容提供者未返回数据流）")
        return importImg(displayName, bytes)
    }

    /** 按需读取：boot v3/v4 只取 kernel 段，其余格式全量读取。 */
    private fun readImageForParse(uri: Uri): ByteArray? {
        val resolver = appContext.contentResolver
        val prefix = resolver.openInputStream(uri)?.use { readUpTo(it, 8 * 1024) } ?: return null
        val header = BootImageParser().parseAndroidBootHeader(prefix)
            ?: return resolver.openInputStream(uri)?.use { it.readBytes() }
        return resolver.openInputStream(uri)?.use { input ->
            skipFully(input, header.kernelOffset)
            val out = ByteArray(header.kernelSize)
            var off = 0
            while (off < header.kernelSize) {
                val r = input.read(out, off, header.kernelSize - off)
                if (r < 0) {
                    throw IllegalStateException(
                        "boot.img kernel 段被截断（offset=${header.kernelOffset}, size=${header.kernelSize}）",
                    )
                }
                off += r
            }
            out
        }
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var off = 0
        while (off < max) {
            val r = input.read(buf, off, max - off)
            if (r < 0) break
            off += r
        }
        return if (off == max) buf else buf.copyOf(off)
    }

    private fun skipFully(input: InputStream, count: Int) {
        var remaining = count.toLong()
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (r < 0) {
                throw IllegalStateException("boot.img 在 kernel 段之前被截断（需跳过 $count 字节）")
            }
            remaining -= r
        }
    }

    // ---- 执行 ----

    /**
     * 执行 GhostKey 提权流水线。日志直通 [appendLog]（<s>/<b>/<-> 协议同源），
     * 阶段进度转译为 "[*] stage n/5" 行。返回 native 退出码（0 = 成功）。
     *
     * [onComplete] 由内部消费；错误与诊断均以日志行呈现（与原引擎行为一致）。
     */
    suspend fun run(
        releaseOverride: String?,
        deviceModel: String,
        managerPackage: String? = null,
        appendLog: (String) -> Unit,
    ): Int {
        val runCounter = runLogStore.nextRunId()
        currentSession?.finish(RunResult.INTERRUPTED, null, 0L)
        currentSession = runLogStore.begin(
            runCounter, deviceModel, kernelRelease(), matchedRelease() ?: "未匹配",
        )
        currentSession?.append("<b> ---- 第 $runCounter 次运行开始 ----")
        try {
            val stages = NativeContract.Stage.ORDERED
            var exit = 1
            engine.executeExploit(
                /* native 详细输出同样落盘：panic 杀进程后归档正文是唯一现场。 */
                onLog = { message ->
                    appendLog(message)
                    currentSession?.append(message)
                },
                onProgress = { stage, status ->
                    val name = stages.getOrNull(stage) ?: "STAGE"
                    appendLog("[*] stage ${stage + 1}/${stages.size} $name: ${status ?: ""}")
                    currentSession?.append("[*] stage ${stage + 1}/${stages.size} $name: ${status ?: ""}")
                },
                onComplete = { code ->
                    exit = code
                    currentSession?.finish(
                        if (code == 0) RunResult.SUCCESS else RunResult.FAILED, null, 0L,
                    )
                },
                releaseOverride = releaseOverride,
                managerPackage = managerPackage,
            )
            return exit
        } finally {
            currentSession = null
        }
    }

    fun stop() = engine.stopExploit()

    // ---- 历史运行日志（panic 后 UI 内存日志丢失，落盘归档是唯一现场） ----

    /** 全部运行记录，按开始时间倒序。IO 密集，请在后台线程调用。 */
    fun runHistory(): List<RunLogMeta> = runLogStore.listRuns()

    /** 某次运行的完整文本（信息头 + 正文），供展示/复制。 */
    fun runLogText(meta: RunLogMeta): String = runLogStore.textOf(meta)
}
