# VeloZip 1.16/1.17 后端兼容性扩展（未发布）

本文记录提交 `2b9d2d6` 将后端 API 基线从 1.18 降至 1.16.5 的实现和验证边界。文件名沿用扩展开发时的命名，项目版本仍为 `1.0.0`，不表示已发布 v1.1.0。1.16/1.17 完成实际启动、协商和重连测试后，才能列入已验证矩阵。

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

- Java 17 / Netty `4.1.68.Final`：65 项单元测试通过，使用临时安装的 JDK 17 执行。
- Java 25 / Netty `4.2.7.Final`：65 项单元测试通过。两组均使用 `--rerun-tasks` 实际重跑。
- Java 25 / Netty `4.1.50.Final`：后端/common 共 61 项测试通过，作为旧 Netty ABI 的补充回归检查。
- `./gradlew build -PtestJavaVersion=25 -PnettyTestVersion=4.2.7.Final`：通过；最后一次构建复用了上述测试缓存。
- 成品包检查通过：后端 class major 不超过 60，插件描述文件为 `api-version: '1.16'`，平台类未进入 shadow jar。

详细命令、模块计数和验证限制见 [2026-09-22 非发布版测试报告](TESTING-20260922.zh-CN.md)。Java 16 运行时尚未实测，字节码检查不能替代运行时测试。本轮未执行任何真实服务端 E2E；1.16/1.17 的候选类名和版本识别也不能替代精确服务端构建的接口核验。
