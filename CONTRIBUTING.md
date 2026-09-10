# 贡献指南

## 基本规则

- 修改 Netty 管道、反射或平台内部接口前，必须核验真实源码或字节码，并在提交说明中列出精确构建及证据。目标范围为 Velocity 3.4.0–4.1.1，以及 Paper/Purpur 1.18–1.21.x、26.1 / 26.2。
- 只操作代理到后端的连接，不改动客户端到代理的链路。
- 优先级：正确性 > 稳定性 > 延迟 > CPU > 带宽 > 功能数量。
- 每次 Netty retain 都必须在所有路径中匹配 release，包含异常路径；测试启用 PARANOID 泄漏检测。

## 开发与验证

1. 创建功能分支，使用 Conventional Commits 风格提交信息，例如 `feat(backend): ...`。
2. 安装 JDK 25（构建用）及 JDK 17（最低运行环境验证用）。
3. 运行 `./gradlew build`，包括单元测试、插件打包和实际成品检查。
4. 分别运行以下兼容测试：

```bash
./gradlew test -PtestJavaVersion=17 -PnettyTestVersion=4.1.68.Final
./gradlew test -PtestJavaVersion=25 -PnettyTestVersion=4.2.7.Final
```

必要时用 `-Dorg.gradle.java.installations.paths=...` 指定 JDK 安装路径。
改动握手或帧格式时必须提高传输协议版本，或说明保持向后兼容的依据。

## 平台兼容策略

目标后端系列包括 1.18、1.19、1.20、1.21、26.1、26.2；支持某个系列不意味着该系列的每个构建都已实测。
当前后端适配器通过已核验的字段类型兼容旧版混淆映射和新版 Mojang 映射，并读取运行中的转发配置。

新增版本时：

1. 比较网络处理器名称、分帧签名、玩家到连接的字段链、插件消息接口和转发认证配置。
2. 补充源码/ABI 依据、回归测试和真实 E2E 测试；只将实际通过的精确构建标记为已验证。
3. 有不兼容差异时扩展或新增 `NetworkAdapter`；公共压缩核心不得引入平台专用类型。
4. 证据写入 [兼容说明](docs/COMPATIBILITY-1.0.0.md) 和 [E2E 报告](docs/E2E-1.0.0.zh-CN.md)，新文档使用中文。

编译依赖使用最旧支持 API：Velocity 3.4.0、Paper 1.18，全部成品字节码目标为 Java 17。
Netty 由平台提供，不得打进插件 jar。SnakeYAML / HdrHistogram 需要隔离，zstd-jni 不得重定位。

## 发布

1. 更新 `gradle.properties`、两端描述文件和 `PLUGIN_VERSION`，同步中文变更日志。
2. 编写 `.github/releases/vX.Y.Z.md` 中文发布说明，包含安装、升级、Java 要求和测试限制。
3. 完成构建与真实服务器矩阵，提交源码及脱敏测试证据。不要提交随机密钥、原始配置、世界或服务端二进制文件。
4. 推送版本标签时，CI 才会创建 GitHub Release，上传两个可部署插件 jar 和 SHA-256 校验清单；推送 main 本身不发布 Release。

历史接口研究保留在 [ANALYSIS.md](docs/ANALYSIS.md)，当前实现以 v1.0.0 兼容说明为准。
