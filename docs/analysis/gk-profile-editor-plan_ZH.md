# 计划：GhostKey profile 参数编辑页（简化版）

状态：待用户批准 · 级别：L（新 UI 面 + 跨模块契约）· 日期：2026-11-07

## 1. 动机

GL 引擎有完整的参数配置页（HOCON 编辑、常规字段 + 高级覆写）；GK 引擎的
profile（boot.img 提取的内核偏移表）只能整体导入/删除，无法查看或微调单个
字段。目标：给 GK 增加一个**简化版**参数页，编辑 profile 文档字段；GL 页面
本轮不动（精简项需单独指认）。

## 2. 现状（事实依据）

- GK profile = `ProfileDocument`（HOCON 全量文档）→ `toKernelProfile()` →
  定长 wire blob。字段全部是**内核偏移**（无 route/fallback/cpu 调参层，
  fput UAF 为单路径，wire 固定）。
- 存储：`ghostkey` 模块 `UserProfileStore`，`filesDir/ghostkey_user_profiles/*.conf`，
  与 GL 的 `user_profiles/` 目录隔离；`ProfileRepository.resolve()` 每次从磁盘重读。
- 执行链：`ExploitEngine.executeExploit` 开头 `resolve(release)` **一次性快照**
  → 序列化 → 交 UserService。运行中不再读存储。

## 3. 方案

### 3.1 编辑模型：直接编辑用户文档（不做 overlay 层）

GK store 本就是全量 HOCON 文档，无需引入 GL 式覆盖层：

- 用户导入的 profile → 原地编辑 `save()`。
- 内置 profile → 先「另存为用户副本」（`documentFileName(release)` 规范名）再编辑；
  原内置层不动，删除用户副本即回退。

### 3.2 字段分组（简化：只暴露 GK 实际可调面）

| 组 | 字段 | 说明 |
| --- | --- | --- |
| 基本 | release（只读）、vuln_type、recommend_shizuku | 标识与开关 |
| 页偏移 | page_offset | KASLR 解算基数 |
| 必填偏移 | timer_link、cred_link、selinux_link、user_ns、root_user、groups、ucounts、task_cred、task_struct_size、cred_uid、kitimer_node | 缺一不可执行 |
| 扩展偏移 | kitimer_size、task_alloc_link、cred_size、cred_cap_*、cred_security、cred_user、cred_user_ns、cred_ucounts、cred_groups | 有默认值（v4 布局），留空即用默认 |

数值支持十六进制（`coerceLong` 已支持 `0x` 前缀），显示/输入均带 `0x` 提示。

### 3.3 校验

保存前 `missingRequiredFields()` 必须为空，否则拒绝保存并标红缺失项；
编辑中实时显示完整性状态（对齐 GL 编辑器的行为）。

### 3.4 竞态保证（本轮核心要求）

1. **单写者**：保存（UI 编辑）是 `ghostkey_user_profiles/` 的唯一写路径；
   执行是唯一读路径，且在执行开始时**一次性快照**。保存与执行不存在共享
   可变状态。
2. **执行互斥**：复用 VM 现有 `running` 大门——`running` 期间禁止进入编辑页
   与保存；GL/GK 共用同一 `running` 标志，两引擎不可能同时处于「编辑保存」
   与「执行」。
3. **会话隔离**：GK 编辑使用独立的 VM 状态，不触碰 GL 的
   `AndroidProfileConfigController` 会话；两套存储目录物理隔离。
4. **线程**：读写均走 `Dispatchers.IO`（与现有导入路径一致）。

## 4. 改动清单

| 文件 | 改动 |
| --- | --- |
| `app/ui/GhostKeyProfileEditorScreen.kt` | 新增：字段分组编辑页 |
| `app/ui/GhostlockUI.kt` | NavigationKey + 入口（GhostKeyProfileCard 已匹配行） |
| `GhostlockViewModel.kt` | GK 编辑状态：加载/草稿/校验/保存（独立于 GL session） |
| strings ×3 | 新增编辑页文案 |
| `ghostkey/` | **零改动**（store/repository 能力已齐备） |

## 5. 验证

- `assembleRelease` 编译门槛（CI）。
- 手工门禁：① 编辑用户 profile → 保存 → 重进显示新值；② 内置 profile 另存
  副本 → 编辑 → 删除副本回退内置；③ 缺字段保存被拒；④ 执行中进入编辑被拒；
  ⑤ 编辑保存后立即执行，日志确认新偏移生效（profile serialized 字节数/字段
  校验行）。
- GL 回归：GL 参数页与执行不受影响（不触碰其会话）。

## 6. 明确不做

- 不给 GK 增加 route/fallback/cpu 调参（wire 无此契约）。
- 不动 GL 参数页（精简项待用户单独指认，另起批次）。
- 不改 wire 格式 / `KernelProfileSerializer` / native 契约。
