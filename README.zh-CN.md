# VeloZip

[English](README.md) | **中文**

在 **Velocity 3.4.0–4.1.1** 与 **Purpur 26.1.2 / 26.2** 后端服务器之间的高性能 Zstd（Level 1）传输压缩 —— 对客户端 ↔ 代理链路**零改动**。

## VeloZip 是什么？

Velocity 等 Minecraft 代理使用原版 zlib 编解码器压缩与后端服务器的连接。VeloZip 用一套专用传输层替换**仅后端链路的**压缩与分帧：数据包批处理 + Zstandard Level 1（通过 `zstd-jni` 原生库），并对极小或不可压缩的批次提供 RAW 旁路。

- **无需客户端模组。** 客户端 ↔ Velocity 连接（压缩阈值、zlib、加密、分帧、ViaVersion 路径）完全不受影响。
- **不建立第二条 TCP 隧道。** VeloZip 在现有的 Minecraft 后端连接上协商（PLAY 状态插件消息），就地切换其传输分帧。
- **不产生双重压缩。** VeloZip 激活时，两端的原版 zlib 处理器均被移除；未压缩的 Minecraft 数据被批处理并仅压缩一次。

完整的源码级网络分析（两个平台的真实 Netty 管道、挂钩点、协议切换安全性论证）请见 [docs/ANALYSIS.md](docs/ANALYSIS.md)。

## 工作原理

```
                Minecraft 客户端
                       │  原版协议 —— 不受影响
                       ▼
           Velocity 3.4.0 … 4.1.1
              VeloZip-Velocity.jar
                       │  现有后端 TCP 连接
                       │  VeloZip 握手（插件消息，PLAY 状态）
                       │  → 协议切换 → VeloZip 分帧
                       │  批处理（≤64 KiB / 500 µs）→ Zstd level 1
                       ▼
           Purpur 26.1.2 或 26.2
              VeloZip-Backend.jar
```

1. 玩家连接到后端后，VeloZip-Velocity 在 `velozip:negotiate` 通道上发送协商消息（协议版本、算法、级别、nonce + 若配置了共享密钥则附带 HMAC-SHA256）。
2. VeloZip-Backend 验证该消息，双方在该连接的事件循环上原子性地替换传输层处理器（分帧解码器 → 双模 VeloZip 解码器；移除原版压缩处理器；安装批处理+Zstd 编码器）。
3. 若任一端缺少 VeloZip，协商在 5 秒后超时，连接保持 100% 原版（或在 `require-velozip: true` 时被拒绝）。

## 环境要求

| 组件 | 要求 |
|---|---|
| 代理 | Velocity 3.4.0–4.1.1（一个插件 jar 覆盖全区间） |
| 后端 | Purpur 26.1.2 或 26.2 |
| Java（代理） | 17+（Velocity 3.x）、21+（3.5.x）、25（Velocity 4.x） |
| Java（后端） | 25（Purpur 26.1.2 / 26.2 要求） |
| 客户端 | 取决于你的代理 —— 无需安装任何东西 |

> **客户端版本范围 = 你的 Velocity 的协议注册表。** VeloZip 从不检查客户端协议版本；它在包层
> 之下压缩代理 ↔ 后端链路。推荐使用 **Velocity 4.1.1** 时，**1.7.2 至 26.2** 的客户端均可原生直连
> —— 两端都不需要 ViaVersion。使用 Velocity 3.4.0（协议注册仅到 1.21.11）时，桥接 26.1+ 客户端
> 需要在代理和后端安装 [ViaVersion](https://github.com/ViaVersion/ViaVersion)（后端还需
> ViaBackwards）；该路径与 VeloZip 正交，且已在 v0.2.0 中重新验证（见下方兼容性）。

## 安装

1. 将 `VeloZip-Velocity-x.x.x.jar` 放入 Velocity 的 `plugins/` 目录。
2. 将 `VeloZip-Backend-x.x.x.jar` 放入 Purpur 的 `plugins/` 目录。
3. 重启。两端在启动时记录各自的版本和平台；每条连接的激活记录为 `VeloZip transport enabled for <server>`。未知/未验证的平台版本会打印警告，但仍会尝试协商（故障安全）。

## 配置

两个插件都会生成 `plugins/velozip/config.yml`（架构相同，Velocity 端额外有按服务器配置的段落）。关键设置：

```yaml
enabled: true
compression:
  algorithm: zstd      # 仅支持 zstd；其他值会导致启动失败
  level: 1             # 仅支持 1
  raw-threshold: 128   # 小于此值的批次以 RAW 帧发送
batch:
  max-size: 65536          # 达到 64 KiB 时发出批次
  max-delay-micros: 500    # ...或在 500 µs 后发出
limits:
  max-frame-size: 1048576
  max-uncompressed-size: 2097152
authentication:
  secret: "CHANGE_ME"  # 留空 = 不认证；两端必须一致
require-velozip: false # true = 拒绝转移到协商失败的已启用后端
debug: false
```

Velocity 端还支持按服务器禁用（`servers: { lobby: { enabled: false } }`）。

## 兼容性

已验证组合（v0.2.0 使用协议 bot 进行的真实 E2E）：

| # | 代理 | 后端 | Via | Bot | 结果 |
|---|---|---|---|---|---|
| A | Velocity 4.1.1 | Purpur 26.1.2 | 无 | 26.1 | ✅ 双端激活，**降低 82.1%** |
| B | Velocity 3.4.0 | Purpur 26.1.2 | 5.11.0 ×3 | 26.1 | ✅ 双端激活，**降低 82.6%**（bot 随后被已知的 Via 5.11.0 上游翻译 bug 踢出 —— 非 VeloZip 问题） |
| C | Velocity 4.1.1 | Purpur 26.2 | 5.12.0-SNAPSHOT ×2 | 26.1 | ✅ 9/10 次会话，**降低 79.2%** |
| D | Velocity 4.1.1 | Purpur 26.1.2 | 5.11.0 ×2 | **1.21.11** | ✅ 双端激活，**降低 83.4%**（bot 侧经 Via 桥的包解码错误 —— 非 VeloZip 问题） |

完整的 Velocity 区间 **3.4.0 → 4.1.1** 与后端区间 **26.1.2 / 26.2** 均由每端同一个插件 jar 支持：
VeloZip 挂钩的网络内部结构（管道处理器名、分帧解码器类、插件消息 API）在该区间内完全一致 ——
已对照 Velocity commit `6b1ea78`（3.4.0）与 `db0a17e`（4.1.1）、Paper `ver/26.1.2` 与 `main`（26.2）
核验（见 [docs/ANALYSIS.md](docs/ANALYSIS.md) 附录）。较旧且已不受上游支持的版本（Velocity 3.4.0
自 2026-08-24 起被 PaperMC 列为 UNSUPPORTED）仍然可用；推荐 4.1.1，因为它原生注册到 Minecraft 26.2
的协议。

超出已验证集合的平台版本（例如未来的 Velocity 4.2）会在启动时打印明确警告，协商仍会尝试；既有的
类型检查保证管道布局一旦出现不兼容，连接自动保持原版。

## 命令

`/velozip status` —— 构建/协议信息和各服务器协商状态。
`/velozip stats` —— 原始/传输/节省字节数、压缩率、RAW/ZSTD 帧计数、平均批次大小、压缩/解压缩延迟 P50/P95/P99、吞吐量。

## 性能

- **真实 E2E，v0.2.0 矩阵**（见兼容性）：四组组合**带宽降低 79.2–83.4%**。
- **真实 E2E，v0.1.0 栈**（Velocity 3.4.0 build 566 ↔ Purpur 26.1.2 build 2592，Java 25）：**带宽降低 83.0%**，压缩 P50 78 µs。
- **JMH**（MC_LIKE 数据集）：仅压缩 ~22 µs @32 KiB、~51 µs @64 KiB —— 均远低于 1 ms。
- **测试**：43 个单元测试在 Netty `PARANOID` 泄漏检测下全部通过。

JMH 方法论、完整结果表格以及 32 vs 64 KiB 批处理大小决策数据请见 [docs/BENCHMARK.md](docs/BENCHMARK.md)。

## 从源码构建

```bash
./gradlew build
```

生成 `velozip-velocity/build/libs/VeloZip-Velocity-0.2.0.jar` 和 `velozip-backend/build/libs/VeloZip-Backend-0.2.0.jar`。需要 JDK 25 工具链（若缺失，Gradle 会自动下载）；生成的字节码目标为 Java 17。

## 故障排查

- **`no zstd-jni in java.library.path`** —— 临时目录可能被挂载为 `noexec`。设置 `-DZstdTempFolder=<可写且可执行的目录>` 或系统级安装原生库。
- **`VeloZip transport not enabled`** 日志 —— 检查 `authentication.secret` 是否一致、两端 `enabled: true`、以及 `velozip:negotiate` 通道可达性。
- 某服务器始终不发生协商 —— 可能是在 `servers:` 中被禁用或后端插件缺失；在 `require-velozip: false` 时连接保持原版（安全回退）。
- **`has NOT been verified with this plugin`** 警告 —— 平台版本比已验证集合更新（或更旧）。VeloZip 仍会协商；若管道布局出现不兼容变更，连接会自动回退为原版。

## 贡献

请见 [CONTRIBUTING.md](CONTRIBUTING.md) 了解基本规则（绝不猜测 Netty/平台内部实现 —— 必须对照真实源码验证并在 PR 中引用证据）、Conventional Commits 工作流，以及添加新平台版本的兼容性策略。

## 许可证

[GPL-3.0](LICENSE)。捆绑 [zstd-jni](https://github.com/luben/zstd-jni)（BSD-2-Clause + 原生库双许可 BSD-3/GPL2，**未重定位** —— JNI 绑定要求保留原始类名）和 [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram)（CC0/公共领域，已重定位）。