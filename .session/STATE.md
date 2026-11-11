# 会话状态存档（2026-11-11，2.0.23 发布后）

> 用途：跨会话记忆。不入库、不提交（`.session/` 未被 git 跟踪）。
> 恢复工作前先通读本文件。

## 一、硬约束（用户明确要求，不可违反）

1. **不在本地构建**：一切构建/测试走 GitHub Actions CI。本地只做 git、编辑、API 查询。
2. 推送方式：`git push origin HEAD:main` 快进 main（不要用 PR）。
3. 版本号在 `app/build.gradle.kts` 的 `appVersionName`。
4. 提交信息 `type(scope): summary`；不要 commit/push 除非任务明确要求。
5. 攻击路径改动必须 `tools/cmp_disasm.py` 对 8 攻击函数做基线复核（基线在用户手上，
   本地无基线），并在提交信息标注。

## 二、设备与环境

- 一加 Ace 5（PKG110），12GB，骁龙 8 Gen 3，8 核
- 内核 `6.1.157-android14-11-o-g7cdd565ce1ed`（**VA39 已实证**：`_text - slide = 0xFFFFFFC080000000`）
- KernelSU 已 root + Shizuku uid=2000
- 本地仓库：`/workspace`，分支 `main`（HEAD=a84c520，2.0.23 已发布，CI 全绿）
- ⚠️ `trae/agent-vc0XOI` 分支已被用户 IDE 同步占用（"feat: update workspace" 系列，
  含 `boot-ace5-trimmed.img` 35MB），与 main 无关，不要动
- ⚠️ gh CLI 凭据会丢；用 `git credential fill` 拿 token 调 GitHub API

## 三、已验证/已证伪的核心结论

1. **calib_b 输出 `0xFFFFFF7F80000000` 是正确的**（第 3、4 次运行同值，score 125/135）。
   - 语义：`page_offset` = PAGE_OFFSET − PHYS_OFFSET（VA↔PFN 差值），见
     `uaf_plant.c object_pfn()`（L335-338）
   - Linux 6.1 arm64：VA39 时 `PAGE_OFFSET = -(1<<39) = 0xFFFFFF8000000000`，
     线性区 `[0xFFFFFF8000000000, 0xFFFFFFC000000000)`，向上生长
     （`_PAGE_OFFSET(va) = -(1UL<<va)`，memory.h）
   - PFN `0xAEE39F` → 物理 ≈43.9GB = 骁龙高位 DRAM bank（32GB+ 起），12GB 设备正常
   - 92% pfn 命中率不可能是错误基址的巧合
2. **原 2.0.23"扫描窗口硬编码错误→假基址"根因分析已证伪，窗口/门控方案作废**。
   照原计划加 `L_expected≈0xFFFFFFBD00000000` 门控会拒绝正确值。
3. slide、selinux target 算术（`slide_addr(SELINUX_LINK)-7 = 0xFFFFFFD03109BD99`）
   均与日志逐位吻合，校准层无错。

## 四、版本线（全部 CI 绿已发布）

- 2.0.19 BTF/校验硬失败/destroy；2.0.20 GK-only 置灰；2.0.21 native 全输出落盘+历史面板
- 2.0.22 历史日志入口（ArrowPreference onClick 必须用方法引用 `actions::onXxx`）
- 2.0.23 = 38c4879 + a84c520：GK 日志去乱
  - relayLog 丢弃 `C98260_STATUS` 行（runChain 是唯一进度源，消灭 stage 行成对重复）
  - `read_enforce` 补尾 `\n`（防粘行吞事件，触碰了 uaf_plant.c，仅日志辅助函数）
  - FormatLogUseCase：GK stage 行按 ok/failed/degraded 染色；error/warn 关键词染色；
    新增 `FormatLogUseCaseTest`（CI 新增 `:app:testDebugUnitTest` 步骤）

## 五、当前活跃问题：Ace 5 提权 panic（2/2 必现）

- 第 3 次：崩在 W1 selinux attempt 2 后；第 4 次：崩在 W2 cred 植入周期内
- **崩溃窗口定位（第 4 次日志关键证据）**：`[stream_backfill] page_freed=` 和
  `writes= hit=` 缺失 → panic 发生在"victim 页已释放、伪造 pattern 尚未写入"的空窗
- 代码依据：`phase=4` 在 L1674 先发（exec 线程立刻 execve → 2048 timer 全释放），
  `reclaim_stream` L1677 才开始；`page_freed` 轮询 200ms（L1475-1481）→ free→reclaim
  空窗内内核触碰裸 buddy 页 → 野指针 → panic
- **加重因素**：
  1. `[child-cred] warn: mlockall errno=12`（uid 2000 RLIMIT_MEMLOCK）→ W2 凭据页
     可能被回收，即使不 panic 也会静默失败（L1721-1729 注释明确此依赖）
  2. trace arming 走 trigger 兜底（直写 enable 被拒，`armed=trigger enable=?`）
  3. `killer errno=3` 良性（exec-ok 已 `_exit(0)`）

## 六、下一步（等第 5 次试验数据）

1. 用户装 2.0.23 跑第 5 次（冷机、单 route、KernelSU 未加载干净启动）
2. panic 则取 `/sys/fs/pstore/console-ramoops-0`，看 `pc :` 落点：
   - posix timer / itimer 路径 → 证实释放后触发假设 → 修复 = 压缩空窗
     （轮询 200ms→50ms / 预铺 pattern / fast 档放宽）
   - cred/selinux 写路径 → W1/W2 写竞争
3. 观察新日志三处：`page_freed`/`writes=` 是否出现；mlockall 是否仍 ENOMEM；
   arming 是 direct 还是 trigger
4. 2.0.24 候选修复清单：空窗压缩、mlockall（root child 后再锁/预 raise limit）、
   arming 确定性

## 七、GK 日志链路速查（2.0.23）

native stdout（`[stage] …` 行 + `\x1eC98260_STATUS` 控制行）→ relayLog（tail 日志文件，
按 `\n` 切行，UTF-8，**丢 STATUS 行**）→ Bridge（onProgress → `[*] stage n/5 name: status`，
onLog 直传）→ 双写：执行 sheet（FormatLogUseCase 染色）+ `RunLogStore` `run-*.log`
（历史面板读它）。染色规则：stage ok 绿/failed 红/degraded 黄/running 蓝，
error 关键词红、`warn:` 黄、其余灰；`<s>/<b>/<->/<k>` 沿用 GL 语义。
