# v1.0.0 后端兼容性与核验依据

目标后端为 Paper/Purpur **1.18–1.21.x、26.1.x / 26.2**；最低版本包含 1.18 本体。
代理维持 Velocity 3.4.0–4.1.1。单个后端 jar 使用 Java 17 字节码；实际运行 Java 仍须满足服务端要求。
支持某个版本系列不代表该系列的每个构建、分支或第三方网络插件组合都已实测。

## Java 与 API

| 后端 | 服务端 Java |
|---|---|
| 1.18–1.20.4 | 17 |
| 1.20.5–1.21.x | 21 |
| 26.x | 25 |

构建使用 JDK 25，所有插件类 `--release 17`。Paper API 固定为
`1.18-R0.1-20211210.020702-61`，`plugin.yml` 的 `api-version` 为 `1.18`。
后端不再继承或直接链接 NMS 类，旧的后端编译占位类已移除。
`paperweight-mappings-namespace: mojang` 保留，以免新版 Paper 对无需重映射的插件做多余转换。

公共传输层针对 Netty 4.1.68 ABI 编译。两个插件包均排除 Netty，使用平台提供的实现；
测试分别在 Netty 4.1.68 / Java 17 和 Netty 4.2.7 / Java 25 上执行。
SnakeYAML 2.6 和 HdrHistogram 都重定位到 `dev.velozip.shaded`；zstd-jni 保留原包名供 JNI 使用。
`verifyArtifacts` 检查实际 jar 的字节码、依赖隔离、描述文件版本和后端最低 API。

## 实际服务端接口

以下来自官方分发 jar，经 `-Dpaperclip.patchonly=true` 解包后用 `javap` 核查。

| 服务端 | 玩家 → 监听器 | 监听器 → 连接 | 连接 → Channel |
|---|---|---|---|
| Purpur 1.18 / 1433 | `EntityPlayer.b: PlayerConnection` | `PlayerConnection.a: NetworkManager` | `NetworkManager.k: Channel` |
| Purpur 1.18.2 / 1632 | `EntityPlayer.b: PlayerConnection` | `PlayerConnection.a: NetworkManager` | `NetworkManager.m: Channel` |
| Purpur 1.19.4 / 1985 | `EntityPlayer.b: PlayerConnection` | `PlayerConnection.h: NetworkManager` | `NetworkManager.m: Channel` |
| Purpur 1.20.4 / 2176 | `EntityPlayer.c: PlayerConnection` | 父类 `ServerCommonPacketListenerImpl.c: NetworkManager` | `NetworkManager.n: Channel` |
| Paper 1.21.11 / 132、26.1.2 / 74 | `ServerPlayer.connection: ServerGamePacketListenerImpl` | 父类 `ServerCommonPacketListenerImpl.connection: Connection` | `Connection.channel: Channel` |

`PlayerChannels` 调用实际 CraftPlayer 的公开 `getHandle()`，按上述两套**类型**寻找唯一实例字段，
包括继承字段，并缓存查找结果。它不依赖混淆字段名、不遍历任意对象图；不存在或存在歧义时拒绝注入。
这同时覆盖旧版带版本号的 CraftBukkit 包以及新版 Mojang 映射运行时。

新增核验用分发文件 SHA-256：

```text
ee2625b06e9c3788c8f3b6b0856552dcef659298008c9466bd073bd0b3371264  purpur-1.18-1433.jar
d95e92082a83ac999695e07968f2acbf288243bc410cdd807b8f043b17fbe39e  purpur-1.18.2-1632.jar
424f51086c9e9090287812a4c21b65d8d69245e7e7dc3729970f12fbf5b366ad  purpur-1.19.4-1985.jar
c8593c0b2760f6ea5bea5a633dfa91eb79005e81453ffd0383ab626f087b5441  purpur-1.20.4-2176.jar
```

下载地址遵循 `https://api.purpurmc.org/v2/purpur/<版本>/<构建>/download`。

## 转发认证配置

源码依据：Paper-archive `ver/1.18.2`，固定提交
[`fc9ee65a32622d03a91db39364b6f106cf88335b`](https://github.com/PaperMC/Paper-archive/tree/fc9ee65a32622d03a91db39364b6f106cf88335b)。
其中 [0274 转发补丁](https://github.com/PaperMC/Paper-archive/blob/fc9ee65a32622d03a91db39364b6f106cf88335b/patches/server/0274-Add-Velocity-IP-Forwarding-Support.patch)
明确从 `settings.velocity-support.*` 加载配置，并在登录时校验转发 HMAC。

| 后端 | 文件 | 启用项 | 实际读取的运行时字段 |
|---|---|---|---|
| 1.18.x | `paper.yml` | `settings.velocity-support.enabled` | `com.destroystokyo.paper.PaperConfig.velocitySupport` |
| 1.19+ | `config/paper-global.yml` | `proxies.velocity.enabled` | `GlobalConfiguration.get().proxies.velocity.enabled` |

读取**已加载的运行时配置**，避免编辑磁盘配置但未重启时错误地信任直连。
只有现代配置类不存在才走旧版路径；现代配置关闭或读取失败时绝不借旧版开关放行。
插件自己的 `authentication.secret` 与 Velocity 的 modern forwarding secret 是两个独立配置，不能混淆。

## 分帧与切换

1.18/1.18.2 的 `PacketSplitter` 继承 `ByteToMessageDecoder`、构造器无参；
新版 `Varint21FrameDecoder` 的构造器签名不同。后端统一使用不链接 NMS 的
`VeloZipBackendFrameDecoder`，按已核验的最多 3 字节、21-bit Minecraft 长度字段分帧，
不完整输入回退 reader index，合法 vanilla 包体继续经过原生解压处理器。
只在帧边界识别 `0x00 0x5A`；协议 1 将该前缀保留作切换标记。
Velocity 端仍继承其平台帧解码器，以保留 PLAY/CONFIG 切换所需的类型查找。

[0769 原生压缩补丁](https://github.com/PaperMC/Paper-archive/blob/fc9ee65a32622d03a91db39364b6f106cf88335b/patches/server/0769-Use-Velocity-compression-and-cipher-natives.patch)
显示旧 Paper 的压缩、解压处理器共用同一个 `VelocityCompressor`，两个 `handlerRemoved` 都调用 `close()`。
因此，后端接受协商后：

1. 在连接 event loop 上安装批处理编码器和双模解码器。
2. 新的出站 VeloZip 帧从 `prepender` context 向 head 写出，跳过原版压缩/分帧；原版处理器暂时保留。
3. 入站尚在路上的 vanilla 包继续由原生解压处理器处理。
4. 收到首个 VeloZip 帧时，同步移除 `decompress`、`compress`、`prepender`，后续全部使用 VeloZip。

同一次 TCP 读取可能同时含末尾 vanilla 帧和首个 VeloZip 帧。
vanilla 帧必须立即向下游交付，避免 Netty 延迟发送 out 列表时解压处理器已被移除。
回归测试还覆盖原 splitter 已经缓存半包时的替换、每字节分片、合包、超长长度前缀、
重复激活、缺少挂钩点、延时批次、断连回收和 native 共享资源提前关闭。

传输协议仍是 **1**，握手和帧格式与 v0.3.0 相同。客户端到代理的协议、压缩和加密保持原平台处理。

### 旧服拒绝消息与代理回退

`javap -c` 检查 Purpur 1.18.2 的 `CraftPlayer.sendPluginMessage`：如果玩家的已注册通道集合
不包含目标通道，该方法直接返回。代理因此在 REQ 之前，先在同一后端连接发送
`minecraft:register`（内容 `velozip:negotiate`），确保后端的认证拒绝消息可以送达。

Netty `ByteToMessageDecoder` 不可共享：移除后不能重新添加同一个实例。
协商拒绝或超时后，代理将已安装的双模解码器设为 vanilla-only 并释放 Zstd 上下文，
保留它的输入缓冲和平台协议状态。单元测试和真实缺插件/拒绝用例覆盖该路径。

## 实测与复现

精确构建和最新结果见 [E2E-1.0.0.zh-CN.md](E2E-1.0.0.zh-CN.md)。
旧版本的既有兼容记录保留在原发布文档中，不自动视为 v1.0.0 的测试结果。

```bash
./gradlew build :velozip-itest:writeBotClasspaths
./gradlew test -PtestJavaVersion=17 -PnettyTestVersion=4.1.68.Final
./gradlew test -PtestJavaVersion=25 -PnettyTestVersion=4.2.7.Final
npm ci --prefix scripts --ignore-scripts
node scripts/e2e-matrix.mjs /absolute/path/to/fixture.json
node scripts/e2e-evidence.mjs /absolute/path/to/build/e2e-matrix-XXXXXX
```

安装 JDK 17、25，必要时用 `-Dorg.gradle.java.installations.paths=...` 提供安装目录。
矩阵 fixture 格式见 `scripts/e2e-fixture.example.json`；全部服务器均使用回环随机端口、
新世界与随机转发密钥。可用 `CASE_FILTER` 选择用例。fixture 中的服务端应来自官方渠道；
运行该测试会为隔离测试实例写入 `eula=true`，需事先接受 Minecraft EULA。
