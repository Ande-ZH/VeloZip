# VeloZip 1.1.0 后端兼容性扩展

本文记录工作区中将后端最低 API 基线从 1.18 降至 1.16.5 的实现和验证边界。它不是 1.16/1.17 的真实服务器 E2E 报告；这些版本完成实际启动、协商和重连测试后，才能列入已验证矩阵。

## 构建基线

| 项目 | 当前值 |
|---|---|
| 后端编译 API | `org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT` |
| 后端描述文件 | `api-version: '1.16'` |
| 后端/common 字节码 | Java 16（class major 60） |
| 代理及工具模块字节码 | Java 17（class major 61） |
| 构建 JDK | Java 25 |
| 传输协议 | v1，未改变 |

后端插件自身需要 Java 16 或更高版本；服务端仍须满足对应 Paper/Purpur 版本的运行时要求。插件包继续排除 Netty、NMS、Bukkit 和 Velocity 平台类，`zstd-jni` 保留原始包名供 JNI 加载。

## 适配范围

`PurpurNetworkAdapter` 的版本族识别扩展为 `1.16`、`1.17`、`1.18`、`1.19`、`1.20`、`1.21`、`26.1` 和 `26.2`。1.16 版本化映射下，玩家连接链使用 `PlayerConnection` 和 `NetworkManager`；`PlayerChannels` 按字段类型寻找唯一实例字段，并覆盖继承字段，不依赖混淆字段名。

1.16 兼容层加入了以下候选类名：

- `net.minecraft.server.v1_16_R1/R2/R3.PlayerConnection`
- `net.minecraft.server.v1_16_R1/R2/R3.NetworkManager`

字段缺失或出现歧义时仍跳过注入并保持原版连接。传输帧、协商消息、认证和回退逻辑保持协议 v1 的原有行为。

## 已执行验证

- `./gradlew build -PtestJavaVersion=25 -PnettyTestVersion=4.2.7.Final`：通过。
- 后端/common 测试使用 Netty `4.1.50.Final`：通过，作为 1.16 旧 Netty ABI 的回归检查。
- 成品包检查通过：后端 class major 不超过 60，插件描述文件为 `api-version: '1.16'`，平台类未进入 shadow jar。

Java 17 测试矩阵尚未在当前环境执行，因为环境只安装了 JDK 25；Gradle 未配置自动下载工具链。1.16/1.17 真实服务端 E2E 也尚未执行，因此本文件不声明这些版本已实测。
