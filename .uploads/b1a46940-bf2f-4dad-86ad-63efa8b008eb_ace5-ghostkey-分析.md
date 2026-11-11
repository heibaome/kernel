# OnePlus Ace 5 临时 root 尝试记录与分析

> 记录时间：2026-11-11
> 数据来源：本机运行日志 3 份、GitHub 仓库源码静态分析、MITRE CVE 记录、仓库文档
> 说明：本文所有结论均标注证据来源；属于推断的部分单独标出，未在真机验证。

---

## 1. 设备信息

| 项 | 值 |
|---|---|
| 机型 | OnePlus Ace 5（`product=PKG110`，`device=OP5D2BL1`） |
| 系统 | ColorOS 17，`PKG110_17.0.0.105 (CNO1C10P02)` |
| 内核 | `6.1.157-android14-11-o-g7cdd565ce1ed` |
| 执行身份 | Shizuku，`uid=2000`（shell），`seccomp=0` |
| 已装 SU 管理器 | `me.weishu.kernelsu`（官方 KernelSU） |

另一台设备（对照）：Xiaomi `2407FRK8EC`，Android 16，内核 `6.1.138-android14-11-g6ab8c9a86a33-ab14396278`。
DirtyFragX 在该机上失败于 `patch: crash_dump64 verification failed / DEVICE NOT VULNERABLE`。

---

## 2. 涉及的工具与其定位（均为本次核实）

| 工具 | 漏洞 | 支持模型 | 现状 |
|---|---|---|---|
| **DirtyFragX**（dugongzi/DirtyFragX，fork 自 mitschud/DirtyFrag） | CVE-2026-43284（xfrm/ESP 解密写页缓存） | 按 KMI，8 个预编译 `.ko`（5.10/5.15/6.1/6.6/6.12/6.18） | 6.1 内核结构性不可用（上游 issue #54 明确说明） |
| **GhostLock**（YuKongA/ghostlock-app） | CVE-2026-43499（futex PI UAF） | 精确 `uname -r` + 每 build 偏移 profile；提取器 exit code 6 = 内核已修 | 本项目主力链；本机 `remove_waiter()` 已修 |
| **GhostSuite / GhostKey**（heibaome/kernel，包名 `com.kernel.ghostsuite`） | GhostLock 链 + **CVE-2026-98260（fput UAF）** | GhostKey 必须依赖 Shizuku；profile 需匹配具体构建 | 本文实际运行的工具；6 天新仓库、1 star、作者自述 Ace 5 必现 panic |
| **Onuce**（cvhhji/Onuce-updates，包名 `com.onuce.dev`） | 64560 / 98260 两个变体 | 打包 onuce ELF + 5 个 SU 后端 + 2 份 kallsyms 符号表 | 无源码、无文档、无第三方验证 |
| **Stellar** | — | Shizuku 定制分支 | **不是 root 工具**，只提供 uid 2000 能力 |

### 关于 Stellar
`Stellar` 为 Shizuku 的定制分支，提供的是 shell（uid 2000）权限，不能提权到 uid 0。
GhostSuite 的 dex 中出现 `com.stellar.server.IStellarService` 等符号，说明它复用了 Stellar 的 IPC 作为 shell 通道。

---

## 3. CVE 版本阈值（MITRE 记录）

### CVE-2026-64560
- 标题：`posix-cpu-timers: Prevent UAF caused by non-leader exec() race`
- 公布：2026-07-29；影响 Linux 5.7+
- 已修版本（≥）：5.10.262 / 5.15.213 / **6.1.180** / 6.6.147 / 6.12.100 / 6.18.41

### CVE-2026-98260
- 标题：`exec: Cleanup POSIX timers right after de_thread()`
- 公布：2026-10-06；影响 Linux 5.7+
- 已修版本（≥）：**6.1.191** / 6.6.158 / 6.12.112 / 6.18.54
- 性质：64560 的**后续修复**（64560 的补丁未完全关闭竞争窗口）

### 本机对照
内核 **6.1.157** 低于 6.1.180 与 6.1.191 → 按版本号判断，两条 CVE 均未合入。
同时 43499 的修复已被一加回移（提取器 `exit code 6`）→ 说明厂商只挑了部分补丁回移，版本号仅能作粗判。

---

## 4. 三次运行的日志分析

### 4.1 时间线

| 时间 | 结果 | 关键事件 |
|---|---|---|
| 22:33:59 | 中断 | 停在 `calib_a` 的 `[kaslr] made=2048`，无后续输出 |
| 22:34:06 | 失败 `code=8` | 工具主动中止，要求重启 |
| 22:34:20 | 中断 | 跑到 inject，`enforce=0`，cred plant 未生效，日志截断 |
| 22:52:13 | 中断 | 完整跑到 inject，6 次 cred plant 全部 `installed` 但无效，22:54:02 截断 |

### 4.2 22:34:06 那次：安全联锁

```
error: previous round left native process(es) [19295,19362]
error: they still pin the pages holding the injected cred (the set includes the AIO holder children from hold_aio_children)
error: killing them would drop that pin and panic the kernel, so this round is aborted instead of cleared
reboot the device, then run the pipeline again
[22:34:07] <b> exploit finished code=8
```

判定：上一轮遗留的原生进程钉住了存放注入 cred 的页面，杀掉它们会触发 panic，因此工具选择中止。
**`reboot the device` 是硬要求**，不是建议。

### 4.3 22:52 那次：定位到失败点

**阶段 1（calib_a）成功** —— KASLR slide：

```
[22:52:21] [calib_a] candidate call sites: 4
[22:52:21]   site=0xffffffe70544bc08 count=2049 slide=0x26fd000000
[22:52:21] slide = 0x26fd000000  _text = 0xffffffe77d000000
```

**阶段 2（calib_b）成功** —— 线性映射基址：

```
[22:52:32] <b> linear map base = 0xffffff7f80000000 (confidence 123/134)
```

**阶段 3（inject）部分成功**：

```
[22:52:32] [SELinux] enforce=1
[22:53:03] [SELinux] enforce=0
[22:53:03] [selinux] already permissive
```

→ 写入原语**有效**：`selinux_state` 被成功写成 permissive（第 8 次尝试）。

**cred 挂载连续失败（6/6）**：

```
[22:53:05] [cred] node=0xffffff89e030a078 task+cred=0xffffff89da734078 off=0x78
[22:53:07] [cred] installed at 0xffffff89e030a078
[22:53:07] [cred] proc Uid:	2000	2000	2000	2000
[22:53:07] [cred] proc CapEff:	0000000000000000
[22:53:07] [cred] self uid=2000 euid=2000
...
[22:53:29] [cred] node=0xffffff89203af410 task+cred=0xffffff89da734078 off=0x1410
```

关键观察：

- 假 cred 每次落在不同页面（`node` 每次不同）→ 堆喷洒是成功的；
- **`task+cred=0xffffff89da734078` 在 6 次中完全不变** → 目标地址由 profile 静态推出，不是每次实测；
- 验证了三条路径（`log_proc_uid(cred_child)`、`getuid()` 自读、`ask_child`）都不是 0 → 挂载未生效。

### 4.4 日志中断的形态

22:54:02 最后一次 `[stream_backfill] writes=400 hit=1` 之后无任何收尾输出（既无 `[cycle]` 也无 `[cred] installed`），属于**流水线未到达任何定义终点**的中断，而不是工具判定的失败。

---

## 5. 根因分析

### 5.1 已排除
- **不是 profile 数值污染（格式）**：作者在 `.session/STATE.md` 记录「假设 B（profile 污染）排除：本次跑的用户 profile 通过了 2.0.25 执行门（admissible = schema_version=2 + 值域全过）」；
- **不是机型识别错误**：`kernel check passed: OnePlus PKG110` 正常；
- **不是写入原语不可用**：SELinux 状态被成功改写即为反证。

### 5.2 结论：`TASK_CRED` 偏移与目标 task 地址可疑

`uaf_plant.c` 中该偏移来自 profile，而非硬编码：

```c
TASK_CRED = offsets->task_cred;      // uaf_plant.c:98
...
cred[2] = g_task + TASK_CRED;        // uaf_plant.c:1406
```

作者自己在代码里留了相同现象的注释：

```c
/* 2.0.31：第 10 次实测 installed 首次达成，但 cred_child 恒 uid=2000。
 * 重定向目标是 g_task（注入进程）的 cred 槽——成功时应变 root 的是本进程 */
```

另外，**本机 build 不在仓库内置 profile 列表内**：

```
ghostkey/src/main/assets/ghostkey_kernel_profiles/6.1.157-android14-11-o-g00d81d97d759.conf
ghostkey/src/main/assets/ghostkey_kernel_profiles/6.1.157-android14-11-o-g606246250429.conf
```

本机是 `-g7cdd565ce1ed`，即运行的是**自 boot.img 提取的候选 profile**（README：「提取结果是尚未验证的候选 profile」）。
`TASK_CRED` 恰好取自 profile，故「目标地址恒定且错误」与「候选 profile 偏移不准」高度吻合。

### 5.3 未能确定的
`g_task` 由 `leak_task()` 从 ftrace 的 `kmem_cache_alloc` 事件中提取（日志 `[task] pid=27955 ... ptr=0xffffff89da733840`）。
该指针是否确实指向注入进程本身，尚未验证——需比对 `leak_task()` 的筛选逻辑（`by=size`、`site`、`hits` 判定）与本进程的实际 task 地址。
**这是「偏移错」与「泄漏到的 task 不对」两种假设的分界点，目前无法从日志单独裁定。**

---

## 6. 设备当前状态与处置

### 6.1 运行态残留
- SELinux：**Permissive**（自 22:53:03 起）；
- 内核堆：本轮已植入 6 份假 cred 结构，可能仍被钉住（`holding zombie` 逻辑）；
- `/data/local/tmp`：`ksud`（4.99 MB）、可能残留 socket / stop 文件。

### 6.2 处置顺序

```bash
getenforce                      # 期望 Enforcing；Permissive 需立刻重启
cat /sys/fs/selinux/enforce
```

1. **完整重启**（关机再开机，非软重启）——这是唯一能清除 permissive、假 cred、钉住页面的操作；
2. 重启后复查：

```bash
getenforce                      # 应为 Enforcing
ls /sys/fs/pstore/              # 存在 console-ramoops* 说明此前发生过 panic
grep -i kernelsu /proc/modules  # 应为空（未成功 root）
```

3. 不需要清理 `/data/adb`：本轮从未成功加载 KernelSU，该目录若有 `ksu`/`ksud` 属历史残留，删除方式为 `rm -rf /data/adb/ksu /data/adb/ksud`（root 下）。

### 6.3 不要做的
- **不要连续重跑**：`code=8` 是安全联锁；跳过它会让「钉住的页 + 假 cred」累积；
- **不要手动杀残留进程**：工具明确提示杀掉会 drop pin → panic；
- **不要在 Permissive 状态下进行支付/银行操作**：此时任何本地漏洞都无需绕过 SELinux。

---

## 7. 若要继续，优先级排序

| 优先级 | 动作 | 目的 |
|---|---|---|
| 1 | 用最新版重新解析 `boot.img`，核对生成的 `task_cred` | 验证 5.2 的假设 |
| 2 | 把 3 份日志提交给仓库作者 | 作者正在按「第 N 次实测」迭代（`.session/STATE.md`），这是其缺失的数据点 |
| 3 | 静态审阅 `leak_task()`（`uaf_plant.c:1995`）与本进程 task 的对应关系 | 裁定「偏移错」还是「task 泄漏错」 |
| 4 | 查看 `FailureDiagnoser.kt` 对 `code=8` 的分类逻辑 | 判断是否还有其他可绕过路径（不建议绕过） |

---

## 8. 本文的证据边界

- **实测数据**：三份运行日志（用户提供）；
- **代码依据**：`heibaome/kernel` 仓库 main 分支源码（`uaf_plant.c`、`ghostkey/`、`.session/STATE.md`、README）、`YuKongA/ghostlock-app`、`dugongzi/DirtyFragX`、`cvhhji/Onuce-updates` 的发布物静态解析；
- **官方记录**：MITRE CVE API（64560 / 98260 的标题、公布时间、受影响版本与已修版本）；
- **未验证**：真机行为（未在 Ace 5 上复现）、`g_task` 正确性、panic 是否实际发生（需 pstore 佐证）；
- **推断项**：5.2 的根因归属、6.1 与 98260 可利用性的因果、Onuce 与 GhostSuite 的代码亲缘关系。

---

## 9. 附：与 root 检测相关的已知事实（仅机制，不含规避方法）

- **SELinux 状态**是成本最低的检测项：读取 `/sys/fs/selinux/enforce`，或以「本应被拒的操作」做 canary 探测；
- **临时 root 不解锁 bootloader**，`ro.boot.verifiedbootstate` 仍为 `green`，硬件级完整性（Play Integrity DEVICE/STRONG）不受影响；这是临时 root 相对持久 root 的唯一结构性优势；
- **SU 管理器包名**在普通应用的 `PackageManager` 枚举中可见，与是否 root 无关；
- **`/proc/modules`**：GhostLock 链的模块自卸载（`return -ENODEV`），GhostKey/KernelSU late-load 的 `kernelsu` 模块在成功后会驻留至重启；
- 本文不包含任何针对特定应用风控的规避方案。
