# 代码审计记录（第二轮）

首轮（`code-audit-2026-11_ZH.md`，基线 `82ab9d2`）只覆盖 `app/` 的数据层与 UI 层，
明确排除了 `ghostkey/`、`tools/extract_rs/`、`profile-core/`、`src/core/` 与 native。
本轮补齐这些盲区，并覆盖 2.0.13–2.0.18 的新增代码（kallsyms 移植、boot.img 流式读取、
导入失败详情）。

- 审计基线：`379a3ef`（2.0.18）
- 范围：`ghostkey/`（Kotlin 引擎 + Shizuku 服务 + native C++）、`tools/extract_rs/`、
  `profile-core/`、`app/.../ghostkey/GhostKeyEngineBridge.kt`
- 方式：只读复核 + 对关键算法用等价实现做交叉验证（见「验证」节），**未改动任何文件**

## 方法

逐行阅读产生行为的代码，不依据命名或注释推断。对移植代码，与上游 Rust 实现逐分支比对
推进/校验逻辑；对 native，追踪 Kotlin→blob→`kernel_table.c` 的实际数据流，确认 Kotlin
侧的可疑值在 native 侧是否被拦截。

严重级别：**P1** = 可达路径上的错误行为（含错误偏移进入内核）；**P2** = 影响有限或输入无界的
缺陷；**P3** = 已知风险，仅记录。

## P1

| 编号 | 位置 | 问题 |
| --- | --- | --- |
| B-01 | `ghostkey/.../ota/BootImageParser.kt:495-496` | **BTF `KIND_FUNC` 推进错误，移植引入。** 上游 `btf.rs:113` 给 `KIND_FUNC` 的 payload 单位是 **0**（`FUNC` 无 payload），且 `btf.rs:208-212` 只在 `FUNC_PROTO`/`DATASEC` 上乘 `vlen`；Kotlin 写成 `kind == 12 → pos += 12 * vlen`。`FUNC` 的 `vlen` 字段是 **linkage**（0=static/1=global/2=extern），不是记录条数。真实 vmlinux BTF 里几乎每个全局函数 linkage 都是 1，**第一条全局 FUNC 之后整条类型流即错位**。 |
| B-02 | `BootImageParser.kt:390-392、396-402`；`data/Profile.kt:52-56` | **结构体成员缺失被填 0，且仍被判为「完整 profile」。** `cred = (memberBitOffset("task_struct","cred") ?: 0)/8` 等 4 处用 `?: 0` 兜底；`missingRequiredFields()` 只判「键是否存在」（`getLong != null`），不判值域。因此 B-01 导致 `task_struct` 成员读不到时，`task_cred = 0` 会作为**完整** profile 落盘并被执行链采用。 |
| B-03 | `ghostkey/src/main/cpp/kernel_table.c:189-193, 200-240`；`main.c:94-98` | **blob 解析失败静默回落内置地址表。** 校验只要求 `timer_link/cred_link/selinux_link` 非 0；`parse_blob` 失败后走 `load_builtin_fallback()` 并 `return 0`，用硬编码 PKJ110 6.6.118 地址继续提权。与 B-02 组合：`task_cred=0` 不触发该三项校验，`task_struct_size/cred_uid/cred_*` 也不校验，0 值直接进入写内核路径。 |
| B-04 | `Cve98260UserService.kt:1162-1167`、`:425-427`；`ExploitEngine.kt:237-240` | **`destroy()` 违反「绝不向 native 进程发信号」的不变量，且 `running` 读取无同步。** `destroy()` 在 `!running.get()` 时对 `exploitProcess` 调 `Process.destroy()`（SIGTERM）；而该文件 425-427 明确写「killing native processes can drop the pin on the cred pages and panic the kernel」。宿主每次收尾都会 `unbind(..., true)`，Shizuku 随即投递 `destroy`，与 `running.set(false)`（104 行）构成竞态，两个分支都错：置 false 早于收尾则 SIGTERM 打在仍存活的 native 上；晚于则有残留服务不退出。 |

## P2

| 编号 | 位置 | 问题 |
| --- | --- | --- |
| B-05 | `BootImageParser.kt:388/513/518/525` | **同名结构体取「第一个」而非「最大的」。** 上游 `btf.rs:226-233 named_struct` 按成员数取最大；Kotlin `firstOrNull`。同名重定义时 size/成员可能取到不完整的那一份。 |
| B-06 | `BootImageParser.kt:417-418` | **gzip 解压无上限。** `GZIPInputStream(...).readBytes()` 无上限，同文件 LZ4 有 `LZ4_MAX_IMAGE` 256 MB 上限（`:546`）。对应 Rust 侧 `boot.rs:408-415`（RS-01）。 |
| B-07 | `GhostKeyEngineBridge.kt:117-137` | **SAF 流被打开两次**（先读 8 KB 头，再重开到 kernel 段）。单次性/昂贵的 provider 会第二次打开失败或重复传输；`kernelSize` 未做上限校验，直接 `ByteArray(kernelSize)`，仅靠外层 `catch (OutOfMemoryError)` 兜底。 |
| B-08 | `ExploitEngine.kt:73-76`；`kernel_table.c:196` | **`releaseOverride` 无二次校验。** 允许用 A 机型的 profile 在 B 内核上执行；native 仅打印 release，从不与 `uname -r` 比对（NV-02）。 |
| B-09 | `kernel_table.c:141-187`；`uaf_plant.c:1373-1406` | **native 侧 12 个 offset 无值域/页对齐校验。** `page_offset/task_cred/task_struct_size/cred_uid/kitimer_node/cred_*` 全无检查；`cred_off_groups+8` 为 u32，`0xFFFFFFFC+8` 可回绕通过 `CRED_PLANT_SIZE` 的 fit 判断。`build_stream_pattern` 用同一个 `g_page_offset` 反算校验，恒成立。 |
| B-10 | `Cve98260UserService.kt:96-105`；`ExploitEngine.kt:206-235` | `catch (e: Exception)` 不含 `Error`：worker 线程内 OOM/`Error` 会跳过 `onComplete` 与 `running.set(false)`，此后每次执行都在 89-93 直接返回；`awaitRun` 无超时、无 `invokeOnCancellation`，协程永久挂起、UI 恒「运行中」。 |
| B-11 | `tools/extract_rs/src/payload.rs:91` | `verify_ops: false`：OTA payload 的 op 数据不做哈希校验即解出，损坏/被篡改的分区数据仍进入解析。 |
| B-12 | `profile-core/.../NativeProfile.kt:445-530, 603-681` | `fromBinary` 缺段/键落默认 0，`from()` 对绝大多数字段 `?: 0u`，`toBinary` 无条件写全；缺失与显式 0 不可区分，decode→encode 把「缺失」固化成 0 偏移。`raw.toUInt()` 对 >u32 的值静默截断。 |
| B-13 | `tools/extract_rs/src/boot.rs:104-128` | LZ4 match 长度先累加、先 `resize`/`push`，最后才与 `max_output` 比较；8 MiB 块可请求 GB 级内存。 |
| B-14 | `uaf_plant.c:1738-1765, 1991-1993, 2087-2095`；`pc98260_rootd.c:113-118, 334` | cred 子进程全屏蔽信号且被 rootd 继承 → rootd 的 `signal(SIGTERM,on_term)` 停机通道失效；父进程异常退出后子进程 `read` 得 0 走 `continue`，成 EOF 空转孤儿；`wait_zombie` 超时后仍阻塞 `waitpid`。 |

## P3（仅记录）

- `BootImageParser.kt:290/299/304/310`：LZ4 block 解码缺边界检查（`src[si]` 未判 `si < size`；`out[out.size-offset]` 在 `offset==0` 或 `> size` 时抛 AIOOBE），报错文案会变成 `IMG 解析失败: Index -5`。
- `BootImageParser.kt:470-503`：`Btf.parse` 无边界校验；`pos += 12*vlen` 可越过 `end` 后 `u32()` 抛越界。
- `BootImageParser.kt:159-160`：`Btf` 构造两次，BTF 被完整解析两遍。
- `BootImageParser.kt:220-223`：`start + kernelSize` 为 Int 加法，u32 高位为 1 时溢出，报 `IllegalArgumentException` 而非 `ImgExtractError`。
- `GhostKeyEngineBridge.kt:150-160`、`:126-134`：`read` 返回 0 时无退避（极端 provider 下自旋）。
- `GhostKeyEngineBridge.kt:37`：`currentSession` 非 `@Volatile`，跨线程写。
- `ImgToProfile.kt:47-53`：`PAGE_OFFSET - DRAM_PHYS_START` 固定按 SM8750 的 0x80000000 计算；MediaTek 平台 DRAM 起始 0x40000000 会得到错误的直接映射基址。`0xA8`/`0x24` 内联偏移为 make.py 硬编码，跨版本未验证。
- `Cve98260UserService.kt:914-928`：`File.delete()` 对不存在文件返回 false，恒打印 "2 node(s) still present"，与 907-912 注释声称消除的日志谎报矛盾。
- `FailureDiagnoser.kt:137-179`：全仓无调用点；且两条规则的前缀要求与实际日志标签不符，永不命中。
- `RunLogStore.kt:319-330`：5 次 `write` 与 `close()` 同一 `runCatching`，写失败即跳过 `close()`。
- `DeviceBinding.kt:76`：`parseKernelVer` 解析失败返回 `OUT_OF_RANGE`（放行）而非 `UNKNOWN`（拦截）。
- `fdt.rs:210-213`：`aligned<=nb<nb` 恒真，校验无效；`derive.rs:654-662`、`btf.rs:71-77` 缺 `checked_add`（当前调用点不可达）；`report.rs:311` release 未转义。
- `uaf_plant.c:354-364`：`g_all_n` 无上限累加，超过 2200 时 `build_watch` 越界读相邻静态变量。

## 验证

B-01 用等价实现交叉验证（`/tmp/btf_probe.py`：同一份合成 BTF，分别按 `btf.rs` 与
`BootImageParser.Btf.parse` 的推进规则走一遍）：

```
=== rust ===   FUNC(linkage=1) 之后
  pos_end= 40 kind=12 name='f'          size=  2 members=[]
  pos_end= 64 kind= 4 name='task_struct' size= 16 members=[('cred', 64)]
  -> task_struct.cred = 8 ; cred.uid = 4        # 正确

=== kotlin === 同一位置
  pos_end= 52 kind=12 name='f'          size=  2 members=[]   # 多跳 12 字节
  pos_end= 64 kind= 0 name='cred'       size= 64 members=[]   # task_struct 被吞
  -> task_struct.cred = MISSING ; cred.uid = 4
```

即：单条 `linkage=1` 的 `FUNC` 就足以让 Kotlin 侧丢掉 `task_struct`；结合 B-02，
真机上要么报「缺字段」（导入失败），要么拿到 `task_cred=0` 的「完整」profile。

## 核实记录（审计发出后的逐条复核）

P1 全部亲自读源复核，两处结论修正如下。

**B-01 确认成立，且比原记录更严重——共三处推进错误**（`BootImageParser.kt:491-501, 506-511`）：

| kind | 含义 | Kotlin 现状 | 上游 `btf.rs` / BTF 规范 |
| --- | --- | --- | --- |
| 12 | `FUNC`（vlen=linkage，无 payload） | `pos += 12 * vlen` | 单位 0，不乘 vlen（`btf.rs:113, 208-212`） |
| 19 | `ENUM64` | 落入 `else → btfPayload` = 0 | 12 × vlen（`btf.rs:120`） |
| 17 | `DECL_TAG` | 落入 `else` = 0 | 8（`btf.rs:118`） |

真实 vmlinux BTF 按 CU 顺序去重发射：`arch/` 等 CU 的全局 `FUNC` 记录（linkage=1）
必然出现在 `kernel/cred.c`、`kernel/fork.c` 的 `task_struct`/`cred` 类型之前，
**第一条全局 FUNC 之后整条类型流即错位**。6.1+ 内核普遍含 `ENUM64`，是第二个
必然错位源。实际后果：`structSize("task_struct")`/`memberBitOffset` 全部落空 →
`ImgToProfile` 不写 `task_cred/task_struct_size/cred_uid` → 导入结果报
「missing fields: offset.task_cred, offset.task_struct_size, offset.cred_uid」，
执行被 `ExploitEngine:87-101` 阻断。**即 B-01 是 boot.img 导入必然缺 BTF 字段
的确定性缺陷，而非直接 panic 路径。**

**B-02 结论收窄（维持缺陷本身，调低复合危害表述）**：`?: 0` 兜底要求
「struct 记录幸存但成员丢失」才产生 0 偏移——B-01 的确定性错位反而使 struct 落空、
走「缺字段」的安全拒绝路径。因此 **B-01 单独出现时不会产生 0 偏移 profile**；
B-02 + B-03 的 0 偏移直达内核写入链需要一个较窄的巧合（错位恰好对齐出同名
struct 而成员缺失）。两者仍须一起修：B-02 是纵深防御，B-03 是 native 侧闸门。

**B-03 逐行确认**（`kernel_table.c`）：

- `:189-193` 完整性检查仅 `timer_link/cred_link/selinux_link` 三个非 0；
  `page_offset/user_ns/root_user/groups/ucounts/task_cred/task_struct_size/
  cred_uid/kitimer_node` 及全部 `cred_off_*` 无任何值域/对齐检查。
- `:233-239` `parse_blob` 失败 → `load_builtin_fallback()` → **`return 0`**，
  以 PKJ110 6.6.118 硬编码地址继续执行，且 `main.c:94-98` 无任何阻断。
- `:180-181` `g_offsets.cred_off_groups + 8` 为 u32 运算，`0xFFFFFFFC + 8`
  回绕为 4，可通过「`cred_off_groups+8 <= cred_size`」边界检查（NV-04 同证）。
- `:204-231` 未收到 blob / 长度非法 / malloc 失败 / 数据不完整 → 全部回落
  fallback 并 `return 0`。

**B-04 逐行确认**（`Cve98260UserService.kt`）：

- `:208` `exploitProcess` 赋值后全仓从不置空；`:1164` `destroy()` 在
  `!running.get()` 时对其 `Process.destroy()`（SIGTERM）。
- `:425-427` 超时路径「left it alive on purpose (killing native processes can
  drop the pin … panic)」——正是该路径返回后 `running` 变 false（`:104`），
  宿主 `unbind` 触发 Shizuku destroy 事务，SIGTERM 打在存活的 pin 持有进程上。
- 竞态另一分支：destroy 事务先于 `:104` 到达时直接 return，服务既不
  `exitProcess` 也无人再调 destroy → 残留 app_process。
- `ExploitEngine.stopExploit():237` 与 `GhostKeyEngineBridge.stop():207`
  全仓无调用者（`stop` 未接 UI），用户无法主动中止运行。

## 结论（核实后修订）

- kallsyms 移植忠实（未发现新问题）；BTF 移植存在**三处**推进错误（FUNC/ENUM64/
  DECL_TAG，见 B-01），其中 FUNC 与 ENUM64 在真实 vmlinux 上是**必然**触发的确定性
  缺陷——boot.img 导入将稳定报缺 `task_cred/task_struct_size/cred_uid`，执行被
  安全阻断。
- 修正后的风险定位：B-01 = 确定的功能失效（导入不可用）；B-02/B-03/B-09 = 「缺失
  即 0、失败即回落」的失效放行设计，0 偏移直达内核写入需要较窄巧合，但一旦发生
  即 panic，须按纵深防御一起修。
- 修复优先级：B-01（恢复 BTF 字段提取）→ B-03（native 解析失败硬失败、fallback
  仅限未收到 blob 且 release 一致）→ B-02（缺失成员拒绝写入）→ B-04（destroy 去
  SIGTERM）。
