# 会话状态存档（2026-11-11，2.0.24 发布中）

> 用途：跨会话记忆。不入库、不提交（`.session/` 未被 git 跟踪）。
> ⚠️ 本文件曾被 IDE 同步/分支切换删除过一次，丢失后按本文件结构重建即可。
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
- **KernelSU 管理器 App 已安装，但设备未 root**（GK 提权从未成功，4 次运行全 panic；
  用户明确纠正过——勿再写"已 root"）；Shizuku uid=2000 shell 会话
- pstore 取证在无 root 时不可读（0400 + SELinux 拒 shell；user 构建 bugreport 也不含）。
  唯一路径 = 某次运行成功 → rootd 临时 root 窗口内快照（2.0.25 候选功能）。
  panic 现场留在 RAM 里不会被清除，但会被下一次 panic 覆盖
- 本地仓库：`/workspace`，分支 `main`（2.0.24 = 946ce4d 已发布 CI 全绿）
- ⚠️ `trae/agent-vc0XOI` 分支被用户 IDE 同步反复重置（"feat: update workspace" 系列，
  8dc8a47 时其 build.gradle.kts 是 "1.2" 且缺 UserService v3/managerPackage），与 main
  分叉，**不要在上面工作**；工作区可能随时被同步切回该分支——动手前先
  `git branch --show-current`，发现被切走：`git diff` 备份 patch → stash -u → checkout main
  → 只重放两分支一致的文件，分叉文件手工重放编辑
- ⚠️ 2026-11-11 IDE 同步事故：main 历史被"pre-termination backup"孤儿化为单提交
  （811d366），**树内容完整**（含执行门/复制按钮/2.0.25）。后果：versionCode 回退
  （已加 floor=500 修复）、git 身份丢失（提交需 GIT_AUTHOR/COMMITTER env）、
  工作区曾被切回 trae/agent-vc0XOI（已切回 main）
- ⚠️ gh CLI 凭据会丢；用 `git credential fill` 拿 token 调 GitHub API
- ⚠️ IDE 同步会删除未跟踪文件（`.session/` 被删过一次）
- GK 内置资产在 `ghostkey/src/main/assets/ghostkey_kernel_profiles/`（13 机型 + index.conf），
  符号地址以**十进制有符号**存储（如 page_offset = -551903297536 = 0xFFFFFF7F80000000）

## 三、已验证/已证伪的核心结论

1. **calib_b 输出 `0xFFFFFF7F80000000` 是正确的**（第 3、4 次运行同值，score 125/135）。
   - 语义：`page_offset` = PAGE_OFFSET − PHYS_OFFSET（VA↔PFN 差值），见
     `uaf_plant.c object_pfn()`（L335-338）
   - Linux 6.1 arm64：VA39 时 `PAGE_OFFSET = -(1<<39) = 0xFFFFFF8000000000`，
     线性区 `[0xFFFFFF8000000000, 0xFFFFFFC000000000)`，向上生长
     （`_PAGE_OFFSET(va) = -(1UL<<va)`，memory.h）
   - PFN `0xAEE39F` → 物理 ≈43.9GB = 骁龙高位 DRAM bank（32GB+ 起），12GB 设备正常
   - 92% pfn 命中率不可能是错误基址的巧合
2. **原"扫描窗口硬编码错误→假基址"根因分析已证伪，窗口/门控方案作废**。
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
- 2.0.24 = 7b04ea5：profile 执行门（用户批准的 L 级方案，参考 GL）
  - `ProfileDocument.validationErrors()`：Long 域值域校验，先于 u32 截断
    （拦 `0x1_0000_0208.toInt()` 静默截断，DeepSeek 复核发现的洞）；规则对齐
    kernel_table.c 默认值表；符号地址判 `>= 0xFFFF000000000000`（十进制负数兼容）
  - `schema_version` 戳：parse 读（缺=0）、导入写 2、`ProfileRepository.resolve()`
    用户层只接纳 admissible（不遮蔽内置）；内置资产豁免版本门但值域全查
  - ExploitEngine 报错分支：stale/invalid/missing 分类提示重导
  - 新增 `ProfileDocumentValidationTest`（规则表 + 内置资产 13 机型全量扫描）
  - wire blob v4 / native 未动

## 五、当前活跃问题：Ace 5 提权 panic（2/2 必现）

- 第 3 次：崩在 W1 selinux attempt 2 后；第 4 次：崩在 W2 cred 植入周期内
- **崩溃窗口定位（第 4 次日志关键证据）**：`[stream_backfill] page_freed=` 和
  `writes= hit=` 缺失 → panic 发生在"victim 页已释放、伪造 pattern 尚未写入"的空窗
- 代码依据：`phase=4` 在 uaf_plant.c L1674 先发（exec 线程立刻 execve → 2048 timer
  全释放），`reclaim_stream` L1677 才开始；`page_freed` 轮询 200ms（L1475-1481）→
  free→reclaim 空窗内内核触碰裸 buddy 页 → 野指针 → panic
- **加重因素**：
  1. `[child-cred] warn: mlockall errno=12`（uid 2000 RLIMIT_MEMLOCK）→ W2 凭据页
     可能被回收，即使不 panic 也会静默失败（L1721-1729 注释明确此依赖）
  2. trace arming 走 trigger 兜底（直写 enable 被拒，`armed=trigger enable=?`）
  3. `killer errno=3` 良性（exec-ok 已 `_exit(0)`）

## 六、假设与裁决

**假设 A（时序空窗）**：free→reclaim 竞争窗口，Ace 5 时序余量不足。
**假设 B（旧 profile 污染，用户提出）**：机制已全部核实——
- 内置资产**无** `o-g7cdd565ce1ed` → 该机 profile 来自 boot.img 导入
- 旧版 UserProfileStore 无版本戳/失效机制、missingRequiredFields 只判键存在
  （2.0.24 已修复"以后"，但**不追溯已导入的旧档**）
- 日志反推 `KITIMER_NODE = 0x78`，与内置同代 6.1.157 资产（kitimer_node=120）
  一致 → 该字段疑似未被污染，其他偏移仍未验证
- **两假设不互斥**，同一份材料可裁决：
  1. `Download/ghostlock-debug-log/<时间>/profile.conf`（第3/4/5次）直接验尸
  2. 用 2.0.24 重导 boot-ace5-trimmed.img 对比（新版写入 schema_version=2）

## 七、第 5-8 次实测结论（2.0.25→2.0.28）

- **第5次（2.0.25）**：假设 B（profile 污染）排除——通过执行门（schema_version=2+值域）
  的干净档依旧 panic；崩点同第 4 次（killer→exec-ok 窗口）
- **第6次（2.0.26 诊断）**：根因锁定——`[killer] sigkill sent` → `page_freed=1` → 崩。
  SIGKILL 跑赢刚 exec 的新镜像 → exit 清扫 2048 k_itimer → 栽赃页被释放 →
  exec/cred 路径踩已释放页。附证：mlockall rlim=65536/65536（uid2000 限额 64KB，
  mlockall 永远失败，只能定向 mlock）
- **第7次（2.0.27 宽限期 200ms）**：exec 桥 panic 根治；**SELinux enforce=0 达成**；
  arming 自动切 direct；新崩点 plant attempt 2 早期
- **第8次（2.0.28 泄漏修复+早期标记）**：plant 连撑 5 轮完整 cycle 不崩；
  **每轮 writes hit=1（forged cred 喷洒全命中）**；但发现**协议死锁**——
  宽限期让新镜像总在 200ms 内自退 → reap 恒 status=0 → one_uaf 恒 -1 →
  `[cred] installed`/ask_child 验证永不可达。协议成功路径 = reap status=9（SIGKILL 赢）
- 中间崩溃（第7次 attempt2、第8次 attempt6 mid-spray）低频偶发，低优先级；
  栽赃页生命周期保护留作候选

## 八、2.0.29（50b0b95，等 CI）：恢复协议时序

`--exec-ok` 睡 1.5s 再自退——SIGKILL 在 200ms 落到已完成 execve 的活进程上
（干净击杀，不复现第6次 mid-exec panic）→ reap status=9 → installed →
ask_child → rooted。第 9 次判读：
- `[cred] installed` + `[cred] answer uid=0` = **提权成功**（后接 rootd→ksud 交接）
- `[cred] still uid=2000` = cred 槽位重定向未生效（查 node[2]=target 链路）
- mid-spray 崩再现 = 查喷洒页生命周期

## 九、GK 日志链路速查（2.0.23+）

native stdout（`[stage] …` 行 + `\x1eC98260_STATUS` 控制行）→ relayLog（tail 日志文件，
按 `\n` 切行，UTF-8，**丢 STATUS 行**）→ Bridge（onProgress → `[*] stage n/5 name: status`，
onLog 直传）→ 双写：执行 sheet（FormatLogUseCase 染色）+ `RunLogStore` `run-*.log`
（历史面板读它）。染色规则：stage ok 绿/failed 红/degraded 黄/running 蓝，
error 关键词红、`warn:` 黄、其余灰；`<s>/<b>/<->/<k>` 沿用 GL 语义。

## 十、竞品情报（2026-11-11 查证）

- 月虹提权助手（yhyun.asia，闭源，QQ/网盘分发）：按机型打包提权 payload + KernelSU
  一键化，ColorOS 17 / OnePlus 13 生态已跑通（DroidWin 提及 Rootline）；公开 PoC 家族
  为 CVE-2026-43499 futex PI UAF（= 本仓库 backend 占位组件），OnePlus/OPPO/realme
  锁 BL。对本项目仅形态参考，无可借代码。用户表示不需要深入调研。
