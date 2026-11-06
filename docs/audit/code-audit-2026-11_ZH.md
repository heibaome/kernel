# 代码审计记录（2026 年 11 月）

Android 数据层 / UI 层首次全量审计。Native 攻击代码（`src/core/`）**不在范围内**：
该项目要求对其执行 `cmp_disasm.py` 与真机门禁，本次未改动这些文件。

- 审计基线：`82ab9d2`
- 范围：`app/src/main/kotlin/com/ghostlock/app/{data,ota,shizuku,ghostkey,ui}`
- 不在范围：`src/core/`、`tools/extract_rs/`、`ghostkey/`

## 方法

以只读方式复核数据层（profile 存储、云端包处理、进程执行、HTTP 分段读取）与
UI 层（ViewModel 状态、Compose 对话框）。下列每条结论均由阅读产生该行为的代码得出，
未依据命名或惯例推断。

严重级别：**P1** = 可达路径上的错误行为；**P2** = 影响有限或输入无界的缺陷；
**P3** = 已知风险，仅记录。

## 发现与修复

| 编号 | 级别 | 位置 | 问题 | 状态 |
| --- | --- | --- | --- | --- |
| A-01 | P1 | `GhostlockViewModel.running` | 普通 `var`，主线程（`beginOperation`）与 `Dispatchers.IO`（`finally` 中的 `endOperation`）都会写。缺少 `@Volatile` 时主线程可能读到过期的 `true`，导致执行键被静默忽略。 | 已修 |
| A-02 | P1 | `RiskConfirmDialog.close()` | 退场动画期间对话框仍可点击。在此窗口内先点**取消**再点**确认**会覆盖 `exitAction`，使得用户已取消的高危操作继续执行。 | 已修 |
| A-03 | P1 | `AndroidGhostlockRepository.runExploitBinary` | `ksu-log-tailer` 线程在创建处即 `start()`，位于持有其唯一 `interrupt()` 的 `try` 之前。中间 `ProcessBuilder` 组装抛异常（日志目录不可写、OOM）会泄漏该线程，此后每个进程生命周期内持续每 200 ms 轮询两个文件。 | 已修 |
| A-04 | P1 | `ota/HttpRangeReader` | 跟随重定向时不做协议校验，接受 `https → http` 降级。profile 包本身不含签名且直接决定内核偏移，被替换即可导致 panic/变砖。 | 已修 |
| A-05 | P2 | `CloudProfilePack.extract` | 仅限制单文档 4 MB，未限制文档数量与累计总量。32 MB 的 deflate 包可膨胀到远超设备内存。 | 已修 + 单元测试 |
| A-06 | P2 | `AndroidGhostlockRepository.readDocument` / `cacheDocument` | 选取的文档读取/复制无大小上限，超大选取可能耗尽堆内存或占满 `filesDir`。 | 仅记录 |
| A-07 | P2 | `UserProfileStore.list` / `documents` | 每次调用都重读所有已存文档，且 `list()` 对每个文件读两次，枚举复杂度随文件数平方增长。 | 仅记录 |
| A-08 | P2 | `CloudProfilePack.extract` | 条目在剥离目录后按基名索引，`a/x.conf` 与 `b/x.conf` 会冲突，后写入者静默生效。 | 仅记录 |
| A-09 | P3 | `AndroidGhostlockRepository.tailKsuLog` | `pending` 行缓冲在遇到换行前无上限；实际上受工具自身写入的日志文件大小约束。 | 接受 |
| A-10 | P3 | `CloudProfileUpdater.update` | `sha256sum.txt` 缺失时跳过完整性校验，仅剩 HTTPS 作为保证。 | 接受 |

## 已实施的改动

- A-01：`GhostlockViewModel.running` 加 `@Volatile`。
- A-02：`close()` 在 `visible` 已为 false 时忽略后续调用，退出动作由先获胜的按钮决定。
- A-03：tailer 创建时不启动，`start()` 移入那个已在 `finally` 中保证 `interrupt()` 的 `try`。
- A-04：读取器记录原始 URL 是否为 `https`，遇到会降级到 `http` 的重定向即抛 `IOException`。
- A-05：`MaxDocuments = 64`、`MaxTotalBytes = 16 MiB`，超出均抛
  `InvalidPackException`（`CloudProfileUpdater.update` 已把它报为 `InvalidPack`），
  并新增用例 `extraction caps the document count` 覆盖。

## 接受的风险与待办

- A-06：需要产品决策（超限时如何提示用户）。建议上限 64 MB，超限以 toast 拒绝。
- A-07：仅性能问题；存储规模是几十份文档而非上千。
- A-08：在 CI 每 release 写一份文档的前提下无实际影响。
- A-09/A-10：两者均受项目可控输入约束。

## 验证

- `./gradlew :app:testDebugUnitTest` —— `CloudProfilePackTest` 含新增的数量上限用例。
- `./gradlew :app:assembleRelease` —— 四处源码改动的编译门槛。

本次未改动 `src/core/` 下任何文件，因此 `cmp_disasm.py` 与真机门禁不适用于该变更集。
