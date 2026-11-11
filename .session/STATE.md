# 会话状态存档（2026-11-12，2.0.34 实施中）

> 跨会话记忆。⚠️ IDE 同步会反复：切回 trae/agent-vc0XOI、孤儿化 main 历史、回滚本文件——
> 动手前必查 `git branch --show-current`；提交需 GIT_AUTHOR/COMMITTER env；用 `git credential fill` 取 token。

## 一、硬约束
1. 不本地构建，一切走 CI；`git push origin HEAD:main`；版本在 app/build.gradle.kts
2. 攻击路径改动在提交信息标注基线复核事项（基线在用户手上）

## 二、设备
- 一加 Ace 5 PKG110，12GB，**未 root**（KSU 管理器仅 App；GK 提权从未成功）
- 内核 6.1.157-android14-11-o-g7cdd565ce1ed；Shizuku uid=2000
- pstore 无 root 不可读；取证靠 fsync 的 run_logs（2.0.30 起可靠）

## 三、核心结论（12 次实测 + BTF 地面真值）
1. profile 全部偏移正确（OTA Range 提取 boot.img → BTF 实测：task_struct.cred=2104(0x838)、
   task_struct=4800、cred.uid@4、cred=176/alloc 192、k_itimer.it@120=0x78 ✓）
2. UAF 写零原语每轮稳定生效（SELinux enforce=0 达成×多轮）
3. "伪造 cred 挂载"路径证伪：内核从未消费 cred 节点（第 10-12 次 installed×N 全无效）
4. 已证伪：profile 污染、SIGKILL 竞争（已修）、到期 erase 触发（2.0.32）、偏移错误（BTF）
5. 特异点：该内核 BTF kind 12/13（FUNC_PROTO/FUNC）编码互换
6. 进程退出后 freed timer 的节点被内核正常摘除 → 死后喷洒的 pattern 无人消费

## 四、2.0.34（实施中）：写零原语直改 cred 内容
- 放弃挂载路径；leak_task 捕获 cred_child 的 cred 对象地址（fork 窗口 trace，
  req=176/alloc=192/184，BTF 实测）
- plant 循环 = 4 个瓦片目标：cred+4(uid,gid) +12(euid,egid) +20(suid,sgid) +28(fsuid,fsgid)
- usage@0 不触碰；写目标是活对象非指针字段 → 误写不 panic
- 判读：`[cred] answer uid=0` → rootd fork → ksud → 终局；候选列表在 [cred-leak] 行

## 五、版本线（CI 全绿已发布）
2.0.19-2.0.23 基础链；2.0.24 profile 执行门；2.0.25 历史复制；2.0.26 诊断标记；
2.0.27 SIGKILL 宽限期（exec 桥根治）；2.0.28 泄漏修复；2.0.29 协议时序恢复；
2.0.30 fsync；2.0.31 双路验证；2.0.32 armed 4s；2.0.33 脏状态预检+一键重启（e2cf327+修复）

## 六、工作区事故记录
- IDE 同步 4 次切走分支/回滚文件；main 历史被孤儿化（versionCode floor=500 已修）
- .ota-work/ 有 OTA Range 提取脚本（zip64→payload→一加变体 proto→XZ 尾部合成）

## 七、GK 日志链路
native stdout → relayLog（120ms tail，丢 STATUS 行）→ sheet 染色 + run_logs fsync
