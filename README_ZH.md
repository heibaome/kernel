# GhostSuite

[English](README.md)

GhostSuite 是双引擎 Android root 提权套件，成功后交由 KernelSU 接管。

- **GhostLock** — CVE-2026-43499（PI-futex），W1/W2/W3 链，App 进程内驱动，使用导出的内核 profile。
- **GhostKey** — CVE-2026-98260（fput UAF），Shizuku + NDK 流水线：校准、注入、校验、接管。必须依赖 Shizuku。

两个引擎共用同一套 UI、执行 sheet 和日志流；引擎卡选择载荷。**同一设备上禁止并发执行两个 exploit**（见 [docs/MERGE.md](docs/MERGE.md)）。

## 使用

1. 从 [KernelSU](https://github.com/tiann/KernelSU)、[BakaSU/ReSukiSU](https://github.com/Baka-SU/BakaSU) 或 [KowSU](https://github.com/KOWX712/KernelSU) 下载并安装管理器 APK。
2. 安装 GhostSuite APK（`com.kernel.ghostsuite`）并打开。从旧 `com.ghostlock.app` 版本升级需先卸载——包名已变更，数据不迁移。
3. 在主界面选择引擎；默认选中当前内核支持的引擎。

**GhostLock** 要求 profile 精确匹配 `uname -r`，请在[支持设备列表](docs/kernel_profiles/SUPPORTED_DEVICES_ZH.md)核对。**GhostKey** 按内核版本范围判断支持性，但 profile 仍需匹配你的具体构建——在引擎卡导入 `boot.img` 自动生成。

若启用 Shizuku（GhostKey 必需），请先通过 ADB 启动，再点击状态卡授权；授权落地后状态卡会即时刷新。

任何自带 `libksud.so` 的 root 管理器都可提供 `ksud` 用于加载模块。内置列表（KernelSU、ReSukiSU、BakaSU、KowSU）会自动检测；其他管理器可在 **设置 → 根管理器** 中手动选择。缺 `ksud` 时 W1/W2 仍可拿到 uid 0，但不会加载模块。

### 与原版 GhostLock / GhostKey 共存

Shizuku 授权**互不冲突**：授权按包名隔离，各应用绑定各自的 UserService 进程；唯一共享的是 Shizuku server 本身（一台设备一个——重启它会波及所有客户端应用，状态卡变 `NOT_RUNNING`，重新启动即可）。**切勿在两个应用中同时执行 exploit**：每个应用的执行互斥锁只看得见自己，同一内核上两个并发堆风水必然崩溃，这不是权限问题而是内核安全问题。

## 获取内核 profile

- **App 内导入（GhostLock）**：**解析完整包链接**接受完整 OTA ZIP 链接；**解析本地镜像**选择 `boot.img`，可附加 `xbl_config.img`；**导入 offsets.conf（v2）**接受 HOCON profile（旧 JSON 经 **导入 offsets.json（v1）** 转换）。
- **boot.img 导入（GhostKey）**：引擎卡解析 boot 镜像，为你的具体内核生成偏移 profile。
- **云端更新（GhostLock）**：**高级设置 → 载入配置 → 检查云端配置更新**拉取 CI 上传的 profile 包；更新源可在同页修改。

提取结果是尚未验证的候选 profile。App 会在执行前检查缺失或无效字段，生成成功不代表设备已获支持。验证和内置登记流程见[适配指南](docs/kernel_profiles/README_ZH.md)；联发科的镜像地址提取见[联发科指南](docs/kernel_profiles/MEDIATEK_ZH.md)。

## 命令行工具

<details>
<summary>导出 profile 并运行原生程序</summary>

需要 Android NDK、`make`、Gradle 和 ADB。通过 adb shell 运行时会跳过 App 的 seccomp 绕过阶段。

```sh
make -C src ghostlock
./gradlew exportKernelProfiles
adb push build/native/ghostlock /data/local/tmp/ghostlock
adb push "build/kernel-profiles/<release>.bin" /data/local/tmp/profile.bin
adb shell chmod 755 /data/local/tmp/ghostlock
adb shell /data/local/tmp/ghostlock --load-prebuilt-profile /data/local/tmp/profile.bin
```

</details>

<details>
<summary>编译并运行偏移提取器</summary>

需要 Rust。提取器支持 boot 镜像、完整 OTA ZIP 或 HTTP(S) 链接。

```sh
(cd tools/extract_rs && cargo build --release)
build/extract/release/ghostlock-extract boot.img --xbl-config xbl_config.img --format conf --out profile.conf
build/extract/release/ghostlock-extract OTA.zip --format conf --out profile.conf
```

在 App 中导入 `profile.conf`。没有 `xbl_config.img` 时省略 `--xbl-config`。若 `remove_waiter()` 已修复，提取器会以退出码 `6` 拒绝该内核。

</details>

## 参考

- [Profile 结构](docs/kernel_profiles/PROFILE_SCHEMA_ZH.md)与[执行默认值](docs/kernel_profiles/defaults_ZH.md)
- [原生核心](src/core/README_ZH.md)
- [引擎融合记录](docs/MERGE.md)
- [新增组件](docs/development/adding-a-component_ZH.md)、[工程规范](docs/development/engineering-standards_ZH.md)和[文档规范](docs/development/documentation-standards_ZH.md)
- [代码审计记录（2026-11）](docs/audit/code-audit-2026-11_ZH.md)——Android 数据层/UI 层发现与修复

## 来源与许可证

采用 [Apache License 2.0](LICENSE)，基于以下项目：

- [YuKongA/ghostlock-app](https://github.com/YuKongA/ghostlock-app)
- [NebuSec/CyberMeowfia](https://github.com/NebuSec/CyberMeowfia)
- [JoinChang/ghostlock-oneplus](https://github.com/JoinChang/ghostlock-oneplus)
- [x-spy/CVE-2026-43499-popsicle](https://github.com/x-spy/CVE-2026-43499-popsicle)
