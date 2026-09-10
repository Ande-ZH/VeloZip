# VeloZip

[中文文档](README.zh-CN.md) · [兼容性说明](docs/COMPATIBILITY-1.0.0.md) · [测试报告](docs/E2E-1.0.0.zh-CN.md)

[![build](https://github.com/Ande-ZH/VeloZip/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Ande-ZH/VeloZip/actions/workflows/build.yml) [![Release](https://img.shields.io/badge/release-v1.0.0-blue)](https://github.com/Ande-ZH/VeloZip/releases)

在 **Velocity 3.4.0–4.1.1** 与 **Paper/Purpur 1.18–1.21.x / 26.1.x–26.2** 后端服务器之间的高性能 Zstd（Level 1）传输压缩 —— 对客户端 ↔ 代理链路**零改动**。

## VeloZip 是什么？

Velocity 等 Minecraft 代理使用原版 zlib 编解码器压缩与后端服务器的连接。VeloZip 用一套专用传输层替换**仅后端链路的**压缩与分帧：数据包批处理 + Zstandard Level 1（通过 `zstd-jni` 原生库），并对极小或不可压缩的批次提供 RAW 旁路。

- **无需客户端模组。** 客户端 ↔ Velocity 连接（压缩阈值、zlib、加密、分帧、ViaVersion 路径）完全不受影响。
- **不建立第二条 TCP 隧道。** VeloZip 在现有的 Minecraft 后端连接上协商（PLAY 状态插件消息），就地切换其传输分帧。
- **不产生双重压缩。** 新帧跳过原版压缩链；后端收到首个 VeloZip 帧后移除原版处理器，确保尚在途的原版包仍能正确解码。

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
           Paper/Purpur 1.18 起
              VeloZip-Backend.jar
```

1. 玩家连接到后端后，VeloZip-Velocity 在 `velozip:negotiate` 通道上发送协商消息（协议版本、算法、级别、nonce + 若配置了共享密钥则附带 HMAC-SHA256）。
2. 后端验证消息后，在连接事件循环上安装双模解码器及批处理编码器。出站立即使用 VeloZip；入站确认首个 VeloZip 帧后再移除原版压缩链。代理以首帧确认协商并切换。
3. 若任一端缺少 VeloZip，协商在 5 秒后超时，连接保持 100% 原版（或在 `require-velozip: true` 时被拒绝）。

## 环境要求

| 组件 | 要求 |
|---|---|
| 代理 | Velocity 3.4.0–4.1.1（一个插件 jar 覆盖全区间） |
| 后端 | Paper/Purpur 1.18–1.21.x、26.1.x / 26.2 |
| Java（代理） | 17+（Velocity 3.x）、21+（3.5.x）、25（Velocity 4.x） |
| Java（后端） | 1.18–1.20.4：17；1.20.5–1.21.x：21；26.x：25 |
| 客户端 | 取决于你的代理 —— 无需安装任何东西 |

> 客户端与后端协议必须匹配，否则需要另行验证的协议转换插件。代理支持某协议不等于能转换后端版本。

## 安装

1. 从 [GitHub Releases](https://github.com/Ande-ZH/VeloZip/releases) 下载 `VeloZip-Velocity-1.0.0.jar` 和 `VeloZip-Backend-1.0.0.jar`（每个 Release 附带两个 jar 以及 SHA-256 `checksums.txt`），或从源码自行构建。
2. 将 `VeloZip-Velocity-x.x.x.jar` 放入 Velocity 的 `plugins/` 目录。
3. 将 `VeloZip-Backend-x.x.x.jar` 放入 Purpur 的 `plugins/` 目录。
4. 重启。两端在启动时记录各自的版本和平台；每条连接的激活记录为 `VeloZip transport enabled for <server>`。未知/未验证的平台版本会打印警告，但仍会尝试协商（故障安全）。

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

v1.0.0 将后端最低版本降至 **1.18 本体**，一个 jar 适配旧版混淆映射和新版 Mojang 映射。支持系列为 1.18、1.19、1.20、1.21、26.1、26.2；未经测试的具体构建不标记为已验证。

精确运行结果见 [v1.0.0 E2E 报告](docs/E2E-1.0.0.zh-CN.md)，接口证据与 Java 矩阵见 [兼容性说明](docs/COMPATIBILITY-1.0.0.md)。v0.3.0 的历史结果保留在原文档中。

后端必须启用 Velocity modern forwarding：

- 1.18.x：`paper.yml` 的 `settings.velocity-support.enabled: true`。
- 1.19+：`config/paper-global.yml` 的 `proxies.velocity.enabled: true`。

转发密钥须与代理一致；插件的 `authentication.secret` 是另一个独立的可选共享密钥。客户端版本须匹配后端，或安装经过验证的协议转换插件。

## 命令

`/velozip config` 显示生效配置（密钥不显示，修改后重启）。代理增加 `/velozip servers` 不可变状态快照与 `/velozip retry <server>` 清除失败冷却；重连后重试。失败冷却最多 30 秒。TX/RX 分开统计；平均批次仅使用 TX 字节；压缩尝试包括回退 RAW。配置拒绝未知键、重复键、错误类型、小数、溢出和显式 null；服务器支持嵌套 enabled 或布尔简写。

`/velozip status` —— 构建/协议信息和各服务器协商状态。
`/velozip stats` —— 原始/传输/节省字节数、压缩率、RAW/ZSTD 帧计数、平均批次大小、压缩/解压缩延迟 P50/P95/P99、吞吐量。

## 历史性能参考

- **真实 E2E，v0.2.0 矩阵**（见兼容性）：四组组合**带宽降低 79.2–83.4%**。
- **真实 E2E，v0.1.0 栈**（Velocity 3.4.0 build 566 ↔ Purpur 26.1.2 build 2592，Java 25）：**带宽降低 83.0%**，压缩 P50 78 µs。
- **JMH**（MC_LIKE 数据集）：仅压缩 ~22 µs @32 KiB、~51 µs @64 KiB —— 均远低于 1 ms。
- **v1.0.0 测试**：64 项单元测试覆盖 Java 17 / Netty 4.1.68 与 Java 25 / Netty 4.2.7 两组运行环境；Netty 泄漏检测级别为 `PARANOID`。上述性能数字来自旧版，不是本版重新测得的基准。

JMH 方法论、完整结果表格以及 32 vs 64 KiB 批处理大小决策数据请见 [docs/BENCHMARK.md](docs/BENCHMARK.md)。

## 从源码构建

```bash
./gradlew build
```

生成 `velozip-velocity/build/libs/VeloZip-Velocity-1.0.0.jar` 和 `velozip-backend/build/libs/VeloZip-Backend-1.0.0.jar`。构建前需安装 JDK 25；所有插件字节码目标均为 Java 17，后端使用固定的 1.18 API。构建同时检查成品 jar 不夹带 Netty/NMS、依赖隔离及版本一致性。

## 项目结构

| 模块 | 用途 |
|---|---|
| `velozip-common` | 平台无关的传输核心：`0x00 0x5A` 分帧、双模解码、Zstd 压缩/解压缩、批处理、协商、指标 |
| `velozip-velocity` | Velocity 代理插件 —— 协商、管道注入、配置、`/velozip` 命令 |
| `velozip-backend` | Paper/Purpur 1.18+ 后端插件 —— 新旧映射与转发配置兼容、管道注入、命令及回归测试 |
| `velozip-benchmark` | JMH 微基准测试（见 [docs/BENCHMARK.md](docs/BENCHMARK.md)） |
| `velozip-itest` | MCProtocolLib E2E bot —— `bot`（26.1 客户端）与 `botLegacy`（1.21.11 客户端） |

旧版本客户端测试使用 `scripts/legacy-bot.mjs`，可复现矩阵由 `scripts/e2e-matrix.mjs` 读取 fixture。新增平台版本需核验实际接口，并扩展 `NetworkAdapter` 兼容逻辑。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 已知限制

- 使用 ViaVersion 桥接客户端/服务器版本差异时，存在已知的**上游**（非 VeloZip）问题 —— 详见兼容性矩阵与 [CHANGELOG](CHANGELOG.md)。

## 故障排查

- **`no zstd-jni in java.library.path`** —— 临时目录可能被挂载为 `noexec`。设置 `-DZstdTempFolder=<可写且可执行的目录>` 或系统级安装原生库。
- **`VeloZip transport not enabled`** 日志 —— 检查 `authentication.secret` 是否一致、两端 `enabled: true`、以及 `velozip:negotiate` 通道可达性。
- 某服务器始终不发生协商 —— 可能是在 `servers:` 中被禁用或后端插件缺失；在 `require-velozip: false` 时连接保持原版（安全回退）。
- **`has NOT been verified with this plugin`** 警告 —— 平台版本比已验证集合更新（或更旧）。VeloZip 仍会协商；若管道布局出现不兼容变更，连接会自动回退为原版。

## 贡献

请见 [CONTRIBUTING.md](CONTRIBUTING.md) 了解基本规则（绝不猜测 Netty/平台内部实现 —— 必须对照真实源码验证并在 PR 中引用证据）、Conventional Commits 工作流，以及添加新平台版本的兼容性策略。

## 许可证

[GPL-3.0](LICENSE)。捆绑 [zstd-jni](https://github.com/luben/zstd-jni)（BSD-2-Clause + 原生库双许可 BSD-3/GPL2，**未重定位** —— JNI 绑定要求保留原始类名）和 [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram)（CC0/公共领域，已重定位）。
