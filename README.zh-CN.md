# VeloZip

[English](README.md) | **中文**

在 **Velocity 3.4.0** 与 **Purpur 26.1.2** 后端服务器之间的高性能 Zstd（Level 1）传输压缩 —— 对客户端 ↔ 代理链路**零改动**。

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
                Velocity 3.4.0
              VeloZip-Velocity.jar
                       │  现有后端 TCP 连接
                       │  VeloZip 握手（插件消息，PLAY 状态）
                       │  → 协议切换 → VeloZip 分帧
                       │  批处理（≤64 KiB / 500 µs）→ Zstd level 1
                       ▼
                Purpur 26.1.2
              VeloZip-Backend.jar
```

1. 玩家连接到后端后，VeloZip-Velocity 在 `velozip:negotiate` 通道上发送协商消息（协议版本、算法、级别、nonce + 若配置了共享密钥则附带 HMAC-SHA256）。
2. VeloZip-Backend 验证该消息，双方在该连接的事件循环上原子性地替换传输层处理器（分帧解码器 → 双模 VeloZip 解码器；移除原版压缩处理器；安装批处理+Zstd 编码器）。
3. 若任一端缺少 VeloZip，协商在 5 秒后超时，连接保持 100% 原版（或在 `require-velozip: true` 时被拒绝）。

## 环境要求

| 组件 | 要求 |
|---|---|
| 代理 | Velocity 3.4.0（build 566+） |
| 后端 | Purpur 26.1.2（build 2592+） |
| Java（代理） | 17+（在 25 上也可正常运行） |
| Java（后端） | 25（Purpur 26.1.2 要求） |
| 客户端 | 任意原版客户端 —— 无需安装任何东西 |

> **关于客户端版本的说明：** Velocity 3.4.0 早于 Minecraft 26.1，协议注册仅到 1.21.11。通过代理连接 26.1 客户端需要在两端安装 [ViaVersion](https://github.com/ViaVersion/ViaVersion) 5.11.0（后端还需 ViaBackwards）。这与 VeloZip 正交 —— VeloZip 压缩的是代理与后端之间交换的任意字节。

## 安装

1. 将 `VeloZip-Velocity-x.x.x.jar` 放入 Velocity 的 `plugins/` 目录。
2. 将 `VeloZip-Backend-x.x.x.jar` 放入 Purpur 的 `plugins/` 目录。
3. 重启。两端在启动时记录各自的版本和平台；每条连接的激活记录为 `VeloZip transport enabled for <server>`。

## 配置

两个插件都会生成 `plugins/velozip/config.yml`（架构相同，Velocity 端额外有按服务器配置的段落）。关键设置：

```yaml
enabled: true
compression:
  algorithm: zstd      # 第一阶段仅支持 zstd；其他值会导致启动失败
  level: 1             # 第一阶段仅支持 1
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

| 代理 | 后端 | 状态 |
|---|---|---|
| Velocity 3.4.0 | Purpur 26.1.2 | **支持** |
| 其他 Velocity | Purpur 26.1.2 | 不支持 |
| Velocity 3.4.0 | 其他 Paper/Purpur | 不支持 |

第一阶段有意只支持一种组合；适配器层（`Velocity340NetworkAdapter` / `Purpur2612NetworkAdapter`）的存在使得未来可以在不触及压缩核心的情况下添加新版本。

## 命令

`/velozip status` —— 构建/协议信息和各服务器协商状态。
`/velozip stats` —— 原始/传输/节省字节数、压缩率、RAW/ZSTD 帧计数、平均批次大小、压缩/解压缩延迟 P50/P95/P99、吞吐量。

## 性能

- **真实 E2E**（Velocity 3.4.0 build 566 ↔ Purpur 26.1.2 build 2592，Java 25）：**带宽降低 83.0%**（每个测量窗口 30.46 KiB → 5.19 KiB），压缩 P50 78 µs。
- **JMH**（MC_LIKE 数据集）：仅压缩 ~22 µs @32 KiB、~51 µs @64 KiB —— 均远低于 1 ms。
- **测试**：40 个单元测试在 Netty `PARANOID` 泄漏检测下全部通过。

JMH 方法论、完整结果表格以及 32 vs 64 KiB 批处理大小决策数据请见 [docs/BENCHMARK.md](docs/BENCHMARK.md)。

## 从源码构建

```bash
./gradlew build
```

生成 `velozip-velocity/build/libs/VeloZip-Velocity-0.1.0.jar` 和 `velozip-backend/build/libs/VeloZip-Backend-0.1.0.jar`。需要 JDK 25 工具链（若缺失，Gradle 会自动下载）；生成的字节码目标为 Java 17。

## 故障排查

- **`no zstd-jni in java.library.path`** —— 临时目录可能被挂载为 `noexec`。设置 `-DZstdTempFolder=<可写且可执行的目录>` 或系统级安装原生库。
- **`VeloZip transport not enabled`** 日志 —— 检查 `authentication.secret` 是否一致、两端 `enabled: true`、以及 `velozip:negotiate` 通道可达性。
- 某服务器始终不发生协商 —— 可能是在 `servers:` 中被禁用或后端插件缺失；在 `require-velozip: false` 时连接保持原版（安全回退）。

## 贡献

请见 [CONTRIBUTING.md](CONTRIBUTING.md) 了解基本规则（绝不猜测 Netty/平台内部实现 —— 必须对照真实源码验证并在 PR 中引用证据）、Conventional Commits 工作流，以及添加新平台版本的兼容性策略。

## 许可证

[GPL-3.0](LICENSE)。捆绑 [zstd-jni](https://github.com/luben/zstd-jni)（BSD-2-Clause + 原生库双许可 BSD-3/GPL2，**未重定位** —— JNI 绑定要求保留原始类名）和 [HdrHistogram](https://github.com/HdrHistogram/HdrHistogram)（CC0/公共领域，已重定位）。
