# GhostLock Suite — 合并说明

将 **GhostLock**（[YuKongA/ghostlock-app](https://github.com/YuKongA/ghostlock-app)，CVE-2026-43499）与 **GhostKey**（[Again1996-bit/CVE-2026-98260-GhostKey](https://github.com/Again1996-bit/CVE-2026-98260-GhostKey)，CVE-2026-98260）合并进同一个 Android APK。

**形态（v2，引擎融合）**：GhostLock 原界面是唯一 UI。主界面 ControlPanel 新增引擎二选一（`EngineCard`：GhostLock / GhostKey，默认选设备支持的模式，GhostKey 选项标注内核支持状态），两个引擎共用 Shizuku、Root 管理器选择、执行 sheet 与日志流（日志协议同源，`<s>/<b>/<->` 标记直通着色）。GhostKey 的主要功能（自动匹配、boot.img 导入生成 profile、已存 profile 选用/删除）以精简形态接入；执行前弹合并安全确认框（启动须知 + 注入风险 + 范围外警示）。早期版本的“外壳首页 + 双入口”（SuiteHomeUI / GhostKeyActivity）已删除。

> ⚠️ 仅供设备所有者授权下的安全研究使用，两个上游项目的原始许可与免责声明继续适用（GhostLock: Apache-2.0；GhostKey: 详见其 README 安全声明）。

## 模块结构

```
ghostlock-suite/
├── app/            # 外壳（com.ghostlock.app）＝原 ghostlock-app，唯一 UI；
│                   #   新增 ghostkey/GhostKeyEngineBridge（引擎桥接层）
├── ghostkey/       # library 模块（com.GhostKey.app）＝原 GhostKey App 的 headless
│                   #   引擎（无 UI：engine/data/device/ksu/native/shizuku）
├── profile-core/   # 原 ghostlock-app 的 profile 导出 JVM 模块
├── src/            # GhostLock native exploit（C++23，make 构建 → libghostlock.so）
├── tools/          # ghostlock-extract（Rust → libextract.so）
```

启动流程：MainActivity（唯一 LAUNCHER）→ GhostLock 主界面 →
- `EngineCard` 选择提权引擎（默认 = 设备支持的模式；GhostLock supported 优先，否则 GhostKey IN_RANGE）
- 选 GhostKey 时：Shizuku 开关强制开启；profile 区块切换为 GhostKey 体系（自动匹配 / boot.img 导入 / 已存 profile 精简列表）；执行按钮先弹合并安全确认，确认后经 `GhostKeyEngineBridge` 驱动引擎，日志直通现有执行 sheet
- GhostKey 的 Material3 独立界面（MainScreen/SettingsScreen/LogViewerScreen/GhostKeyActivity）与外壳首页已删除，Compose 依赖从 ：ghostkey 模块移除

## 已解决的合并冲突

| 冲突点 | 处理 |
|---|---|
| 双 LAUNCHER MainActivity | GhostKey 的入口改名为 `GhostKeyActivity`，去掉 intent-filter；外壳成为唯一启动入口 |
| 双 `ShizukuProvider`（authorities `${applicationId}.shizuku` 重复，merger 必炸） | 仅保留外壳 App 的 provider 声明；两模块各自经 `Shizuku.bindUserService` 绑定自己的 UserService，互不影响 |
| 双 Application 类 | 删除 `Cve98260Application`（其 binder listener 均为空实现，纯 no-op）；统一使用 `GhostlockApplication`。`ExploitViewModel(AndroidViewModel)` 走默认工厂，不依赖原 Application |
| assets 同名冲突（`kernel_profiles/index.conf` 等 3 个文件） | GhostKey 资产目录改名 `kernel_profiles` → `ghostkey_kernel_profiles`，`BuiltinProfileCatalog.DIR` 同步更新 |
| `app_name` 字符串合并覆盖 | GhostKey 改用独立 `ghostkey_name`（MainScreen 标题引用同步更新） |
| 图标资源重复（ic_launcher_foreground 等） | 删除 library 侧未引用的 launcher mipmap/drawable |
| 签名 | 移除 GhostKey 自带 release 签名配置（keystore 本就不入库），统一用外壳 `local.properties` 的 KEYSTORE_* 签名链 |
| R8 keep 规则 | GhostKey 的 proguard-rules.pro 改为 `consumerProguardFiles` 随 library 下发，外壳 release R8 自动消费（Shizuku UserService / AIDL keep）。全局选项（`-repackageclasses`/`-allowaccessmodification`/`-assumenosideeffects`）不能出现在 consumer 规则里（AGP `exportReleaseConsumerProguardFiles` 会直接失败），统一搬到 `app/proguard-rules.pro` |
| ABI | 外壳 `splits.abi` 已限定 arm64-v8a；library 增加 `ndk.abiFilters += "arm64-v8a"` 与之对齐 |
| AGP/Kotlin 版本 | 统一为外壳的 AGP 9.1.0 + Kotlin 2.4.20；library 弃用 `kotlinOptions`，改用 `jvmToolchain(21)` |

## 构建

环境要求与 ghostlock-app 一致：Android SDK（compileSdk 37）+ NDK（或 ONDK）+ CMake 3.22.1 + aarch64 Rust target（libextract）。

```bash
# local.properties 需配置 sdk.dir / ndk.dir（或 ONDK），以及可选 KEYSTORE_*
./gradlew assembleRelease
# 产物：app/build/outputs/apk/arm64/release/*-arm64-v8a-release.apk
```

如需单独跑 GhostKey 的 JVM 单测：`./gradlew :ghostkey:testDebugUnitTest`

## GitHub Actions 自动打包

沿用仓库自带的 `.github/workflows/build.yml`（原 ghostlock-app CI：ONDK 一体化 NDK+Rust、三平台 extractor、prerelease 自动发布、Telegram 通知），针对合并工程做了以下适配：

1. **新增 `Set up Android SDK components for :ghostkey`**：`:ghostkey` 模块的 `externalNativeBuild(cmake)` 需要 SDK 侧 `cmake;3.22.1`（ONDK 不含 CMake）与 AGP 9.1 默认 `ndk;28.2.13676358`（两个版本号已对照 Google 官方仓库索引 repository2-3.xml 实锤存在）。ghostlock 的 make/cargo 链仍走 ONDK，互不影响。
2. **APK 输出路径**：`splits.abi = arm64-v8a` 使输出落在 `build/app/outputs/apk/arm64/release/`，rename 步骤的 glob 已同步修正。
3. **命名/文案**：APK 与 artifact 改名 `GhostLock-Suite-release`，prerelease notes 标题与文件清单同步（release notes 生成、sha256 循环、动态遍历 artifacts 的 Telegram 脚本均已核对一致）。
4. 触发器/缓存/secrets 原样保留：`SIGNING_KEY`、`KEY_STORE_PASSWORD`、`ALIAS`、`KEY_PASSWORD` 四个 secrets 配置后 main 分支自动正式签名，否则 debug 签名 fallback。

## 竞态审查（合并后专项）

同进程后两个提权模块共享的「状态」边界发生变化，已逐项审查：

| # | 问题 | 性质 | 处置 |
|---|---|---|---|
| 1 | 两模块同用 `filesDir/user_profiles/`：GL 的 UserProfileStore 列出目录下**所有文件**（不筛扩展名）并解析 release，GK 写入 `user-<release>.conf`（不同 CVE 的偏移 schema）→ 互相把对方的提权偏移表当成本模块 profile 套用 | **确定性数据污染**（非概率性，必发） | 已修：GK 目录改 `ghostkey_user_profiles/`、`ghostkey_run_logs/`，与外壳隔离 |
| 2 | Shizuku 权限请求码两侧同是 `4815`：同进程共享 Shizuku 静态 listener 注册表，同码请求会互相覆盖 pending 授权流 | 真竞态 | 已修：GK 改 `REQUEST_CODE = 9826`，GL 保持 4815 |
| 3 | 运行互斥只看自己（GL `if(running)` / GK `if(isRunning)`），两个内核 exploit（PI-futex 与 UAF）可并发攻击同一内核：堆布局互相破坏、GL W1 的 SELinux 全局翻转中途改变 GK 校准环境、双 KernelSU handoff 时两个 ksud 抢状态 | 真竞态 | 已修（VM 层强制互斥）：融合后 GL `runExploit` 与 GK `runGhostKey` 共用 ViewModel 单一 `running` 标志（`beginOperation()`），运行中引擎切换亦被锁（`onEngineSelected` 直接返回）；同进程内并发已不可能。残余向量见下节 |
| 4 | 顺序执行下的环境依赖：GL 成功后 KernelSU 常驻会改变 Shizuku uid 模式（shell→root），GK 的 shell uid=2000 前提变化 | 环境依赖（非并发） | 未修：需真机验证 GK 在 KernelSU 已常驻环境下的行为 |

> 引擎互斥的权威实现：`GhostlockViewModel.running`（单一布尔标志）+ `beginOperation()` 入口检查，
> 双引擎执行路径与引擎切换均经过它；本文档与 README 仅作警示补充。

## 残余风险 / 待真机验证

1. **应用双开 / 工作资料（残余并发向量）**：同一 `running` 标志只在单进程内生效。系统级应用双开或工作资料会产生独立进程，App 层无法约束——根治需内核级/设备级隔离。**同一设备同一用户下禁止并发执行两个模块**：两 exploit 的堆风水假设互斥，SELinux 是全局状态，双 ksud handoff 会争抢 /data/adb 状态。
2. **KernelSU 常驻后的跨模块行为**：GL 提权成功→KernelSU 常驻后，GK 的 Shizuku 探测（uid 模式）与 PrivilegeGate 行为需真机验证。另需验证顺序执行：GL handoff 后再跑 GK，其 KsudStage 对已运行的 ksud 是否幂等。
3. **双 Shizuku UserService 共存**：两个 service 类与 AIDL 包名不同，理论上无冲突；建议在同一设备上先后跑通两个模块的 Shizuku 授权路径。
4. GhostLock 的 native（make + Rust）构建链未动，按原项目文档操作。

## 与原项目的差异清单（快速 diff 定位）

- `settings.gradle.kts`：+`:ghostkey`，rootProject.name → `GhostLock Suite`
- `build.gradle.kts`（根）：注册 `com.android.library` / `org.jetbrains.kotlin.android` 插件版本
- `app/build.gradle.kts`：+`implementation(project(":ghostkey"))`
- `app/ui/MainActivity.kt`：注入 `GhostKeyEngineBridge`；引擎切换 / GhostKey profile 导入选用删除的 action 接线
- `app/ghostkey/GhostKeyEngineBridge.kt`：新增（GhostKey 引擎桥接层：执行 / Shizuku / profile 库）
- `app/res/values*/strings.xml`：新增 engine_* / ghostkey_* 字符串（en/zh/pt）
- `ghostkey/`：见上表（build.gradle.kts 重写为 library、去 Compose/UI、Manifest 瘦身、资产改名、Application 删除）
