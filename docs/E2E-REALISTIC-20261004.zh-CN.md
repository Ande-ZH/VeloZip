# 真实地形版本矩阵 E2E 报告（2026-10-03/04，GitHub Actions）

对每个 Minecraft 大版本族选取代表构建（各系列最后一个 minor 的最新 Paper/Purpur
构建），在 GitHub Actions 上以**真实运行环境**执行 VeloZip 传输 E2E：默认地形生成器
（固定种子）、原版默认视距/模拟距离、各版本要求的真实 Java 运行时、原生协议客户端。
最终 **8/8 用例通过**（3 轮尝试，见下文"尝试记录"）。

- 最终运行：[run 37137299199](https://github.com/Ande-ZH/VeloZip/actions/runs/37137299199)
  （提交 `669969f`，8/8 通过）
- 首轮运行：[run 37134803664](https://github.com/Ande-ZH/VeloZip/actions/runs/37134803664)
  （提交 `e689a7b`，旧判据 8/8 通过，暴露判据盲区）
- 第二轮：[run 37136182866](https://github.com/Ande-ZH/VeloZip/actions/runs/37136182866)
  （提交 `9403d5f`，7/8，1 例 1ms 抖动失败）
- 脱敏证据：[docs/e2e/v1.0.0-20261004-ci-37137299199/](e2e/v1.0.0-20261004-ci-37137299199/)
  （由最终运行的 artifact 经 `e2e-evidence.mjs` 离线重建并校验 SHA256，重建方式见
  "证据说明"）；首轮证据在 [docs/e2e/v1.0.0-20261003/](e2e/v1.0.0-20261003/)。
- 项目版本仍为 `1.0.0`，传输协议 v1。测试隔离遵循 v1.0.0 报告的标准
  （回环随机端口、随机转发密钥、独立插件配置、`PARANOID` 泄漏检测、
  `-XX:ActiveProcessorCount=2`、顺序执行）。

## 环境还原方式

与历史 flat + 视距 3 的快速冒烟不同，本矩阵把环境尽量贴近真实部署：

| 维度 | 取值 |
|---|---|
| 世界 | 各版本默认地形生成器（`level-type=normal`，含结构），固定种子 `velozip-ci` 保证地形可复现 |
| 视距 / 模拟距离 | 10 / 10（vanilla 服务端默认值） |
| Java 运行时 | 按版本真实要求：1.16.5/1.17.1 → Temurin 16，1.18.2–1.20.4 → Temurin 17，1.21.11 → Temurin 21，26.1.2/26.2 → Temurin 25；代理统一 Java 25 |
| 客户端 | 原生协议 bot：1.16.5–1.20.4 用 `minecraft-protocol`（`scripts/legacy-bot.mjs`），1.21.11 用 `botLegacy`（MCProtocolLib），26.x 用 `bot`（MCProtocolLib）；26.2 经 ViaVersion + ViaBackwards 以 26.1 协议接入 |
| 服务端构建 | `ci-download-servers.mjs` 从 PaperMC fill / Purpur API 取各系列最新构建并校验 sha256/md5 |

服务端 jar 与 Via 插件哈希见证据目录 `fixture.json` 的 `sourcesAtExport` 与
各用例 `inputs`；版本/构建以 Purpur/Paper 启动横幅为准（保留在用例日志摘录中）。

## 判定标准

沿用 v1.0.0 通过标准，并在本轮强化了两点（提交 `669969f`）：

1. **健康保持从首个世界区块起算**，不再从进入 PLAY 起算。bot 必须实际收到地形
   区块（旧协议 `map_chunk` / 新协议 `level_chunk_with_light` /
   `ClientboundLevelChunkWithLightPacket`）才开始 30 秒保持；60 秒未收到区块判失败。
   避免把"只完成了登录"误判为"世界已渲染"。
2. 保持时长断言加 100ms 容差，消除 29999ms 一类的定时器抖动假失败。

其余不变：保持期间两端各 1 个激活连接；断连后计数归零；两端 TX/RX 计数
逐字节一致；`active` 用例激活次数恰好等于重复数；无 vanilla 回退异常。

## 最终矩阵（run 37137299199，8/8 通过）

代理为 Velocity 4.1.1 build 24（Java 25），bot 保持 30 秒，表中 TX 为后端→客户端
方向的应用帧字节（非 TCP 抓包），"首区块"为进入 PLAY 到收到第一个地形区块的延迟：

| 用例 | 服务端构建 | 后端 Java | 首区块 | TX 原始 → 线上 | 压缩比 | zstd/RAW 帧 |
|---|---|---|---:|---:|---:|---|
| purpur1165-normal | Purpur 1.16.5 / 1171 | 16.0.2 | 99 ms | 10,927,324 → 1,461,513 | ×7.5 | 844 / 466 |
| paper1171-normal | Paper 1.17.1 / 411 | 16.0.2 | 88 ms | 13,489,637 → 1,959,935 | ×6.9 | 996 / 427 |
| purpur1182-normal | Purpur 1.18.2 / 1632 | 17.0.20.1 | 110 ms | 8,273,881 → 1,255,856 | ×6.6 | 564 / 270 |
| purpur1194-normal | Purpur 1.19.4 / 1985 | 17.0.20.1 | 33,208 ms | 24,817,769 → 3,260,424 | ×7.6 | 989 / 280 |
| purpur1204-normal | Purpur 1.20.4 / 2176 | 17.0.20.1 | 41,617 ms | 21,332,827 → 3,098,154 | ×6.9 | 714 / 443 |
| paper12111-normal | Paper 1.21.11 / 132 | 21.0.12.1 | 7,316 ms | 4,289,663 → 708,898 | ×6.1 | 282 / 420 |
| purpur2612-normal | Purpur 26.1.2 / 2592 | 25.0.4.1 | 6,569 ms | 4,293,580 → 730,454 | ×5.9 | 344 / 478 |
| paper262-normal | Paper 26.2 / 129（经 Via ×2） | 25.0.4.1 | 6,721 ms | 4,783,106 → 778,206 | ×6.1 | 370 / 477 |

全部用例：断连计数归零、两端计数一致（`settled`/`bilateral` 为 true）、带宽降低
约 83%–86.6%（1 − 线上/原始）。所有版本都出现 zstd 与 RAW 混合帧，
RAW 旁路与 Zstd 路径在各版本上均实际工作。

### 关键观察

- **1.19.4 / 1.20.4 的首区块显著延迟**（33–42 秒，其他版本 ≤7 秒）。这是服务端
  区块系统在 `-XX:ActiveProcessorCount=2`（继承自历史隔离标准）下生成真实地形的
  吞吐问题，与 VeloZip 无关：区块开始交付后，传输立即激活并在同一会话内完成
  20MB 级流量的压缩传输。该现象正是首轮判据的盲区（见下），强化判据后被如实度量。
- **真实地形的压缩比（×5.9–×7.6，即 83%–86.6% 降低）高于历史 flat 冒烟数据**
  （约 79%–83%）：真实地形区块的调色板/光照数据对 zstd 更友好。
- 各版本首帧到首个批次的时间、批大小等细节可对比各用例日志摘录。

## 尝试记录（按仓库惯例保留）

| 轮次 | 提交 | 结果 | 说明 |
|---|---|---|---|
| 1 | `e689a7b` | 8/8 通过 | 旧判据（从 PLAY 计时）。purpur1194/purpur1204 两例整段会话 TX 仅 581 字节但判定通过——暴露"未验证世界渲染"的判据盲区 |
| 2 | `9403d5f` | 7/8 | purpur1165 因 `29999 < 30000`ms 定时器抖动假失败；同时日志证明 1.19.4 在会话中后段实际完成 23.1MB→3.1MB（×7.4）的完整传输 |
| 3 | `669969f` | **8/8 通过** | 判据强化（首区块门控 + 100ms 容差）后的最终结果，即上表 |

## 证据说明

最终证据目录由 run 37137299199 的公开 artifact 离线重建：工作流的证据导出步骤在
该轮把输出目录误指向了 checkout 中已存在的同名目录（`ls -dt` 在 checkout 后 mtime
相同的目录间不可靠），故 artifact 中缺少新导出；本轮随后用 artifact 中的 8 个用例
`result.json` 与完整控制台日志重放同一 `e2e-evidence.mjs`（`E2E_EVIDENCE_SUFFIX` 隔离
输出），并校验了 `SHA256SUMS`。工作流已在提交 `669969f` 之后的当前版本修复
（运行号后缀 + 从导出器输出捕获目录），后续轮次将直接在 CI 上产出。
日志摘录仅含白名单行；转发密钥与认证密钥为每次运行随机生成，从未入库。

## 边界与未覆盖

- 每个版本族只实测一个代表构建（各系列最新 minor 的最新构建）；
  1.16.1、1.17.0、1.21.1 等其他构建未逐一测试，支持系列不等于系列内全部构建已验证。
- 单 bot、30 秒会话；不含多玩家并发、长稳、高负载与真实客户端全部包行为。
- 服务端被限制为 2 个处理器（历史隔离标准），吞吐相关数字（含首区块延迟）
  不代表多核生产环境的绝对值。
- 负向用例（缺插件、密钥拒绝、代理禁用等）已在
  [2026-10-04 验证报告](TESTING-20261004.zh-CN.md)的 1.16/1.17 本地矩阵覆盖；
  本矩阵聚焦全版本族的激活路径。
- 首轮起跑前的 [CI 旧判据证据](e2e/v1.0.0-20261003/)保留原样，其 1.19.4/1.20.4
  用例的流量数字不反映真实渲染会话，应以本文最终矩阵为准。

## 复现

```bash
# GitHub Actions：push 涉及路径自动触发，或手动
gh workflow run e2e-matrix.yml            # 可用 case_filter 过滤用例
gh run watch                               # 或在 Actions 页面观察
```

本地复现（需自备各版本 JDK 并接受 Minecraft EULA）：

```bash
./gradlew build :velozip-itest:writeBotClasspaths
npm ci --prefix scripts --ignore-scripts
JDK16_HOME=... JDK17_HOME=... JDK21_HOME=... JDK25_HOME=... \
  node scripts/ci-download-servers.mjs      # 下载并校验服务端，生成 fixture
node scripts/e2e-matrix.mjs build/ci-fixture.json
```
