# VeloZip 编译占位类（stubs）说明

VeloZip-Velocity 需要调用 Velocity 代理的**内部类**（`com.velocitypowered.proxy.*`），
这些内部实现不在 `velocity-api` 构件里，也没有对应的 Maven 构件。为此
`velozip-velocity` 维护一组**仅编译期**的占位类（stubs）：只声明 VeloZip 实际用到的
成员签名，普通方法体一律抛出 `UnsupportedOperationException("stub")`（构造器为空
实现，仅用于满足签名）。运行时插件链接代理自带的真实类；占位类本身**永不进入
成品 jar**。

本文件同时记录历史后端占位类的来源与移除原因，作为
[ANALYSIS.md](ANALYSIS.md) §9 第 4 条所引用的核验记录。

## 打包隔离

占位类位于独立的 `stubs` sourceSet（见
[velozip-velocity/build.gradle.kts](../velozip-velocity/build.gradle.kts)），共有三道防线：

1. `compileOnly(sourceSets["stubs"].output)` —— 只参与编译，不进成品 jar 与生产运行时
   （单元测试运行时另有 `testImplementation(sourceSets["stubs"].output)`，属测试专用）；
2. shadowJar `exclude("com/velocitypowered/proxy/**")` —— 双保险；
3. 根构建的 `verifyArtifacts` 任务会打开实际成品 jar，逐条目确认不存在任何
   `com/velocitypowered/` 前缀的条目。

## 现存占位类（5 个）

真实类均位于 Velocity 仓库 `proxy/src/main/java/` 下，签名基线为
**Velocity 3.4.0 commit `6b1ea78`**（官方 build 566）；3.4.0 → 4.1.1 的签名稳定性
核验见 [ANALYSIS.md §10.2](ANALYSIS.md)。

| 占位类 | 声明的成员 | VeloZip 使用点 |
|---|---|---|
| `connection.MinecraftConnection` | `getChannel()`、`eventLoop()` | [VelocityNetworkAdapter](../velozip-velocity/src/main/java/dev/velozip/velocity/adapter/VelocityNetworkAdapter.java) 取后端 channel 与事件循环 |
| `connection.backend.VelocityServerConnection` | `getConnection()`、`getServer()`、`sendPluginMessage(ChannelIdentifier, byte[])` | 定位后端连接；发送 `minecraft:register` 与协商 REQ |
| `connection.client.ConnectedPlayer` | `getConnectedServer()`、`getConnectionInFlight()` | 从 API `Player` 落到内部实现，找到已连/在途的后端连接 |
| `protocol.ProtocolUtils` | `Direction` 枚举 | 构造解码器方向参数（CLIENTBOUND，匹配 `BackendChannelInitializer`） |
| `protocol.netty.MinecraftVarintFrameDecoder` | 构造器 `(Direction)`、`decode` | [VeloZipVelocityFrameDecoder](../velozip-velocity/src/main/java/dev/velozip/velocity/network/VeloZipVelocityFrameDecoder.java) 的**运行时父类** |

`MinecraftVarintFrameDecoder` 的真实类还有 `setState(StateRegistry)`，PLAY↔CONFIG
状态切换会按类查找并调用它（[ANALYSIS.md §2.4](ANALYSIS.md)）——这正是双模解码器
必须以该类型的**子类**占据 `frame-decoder` 名位、而不能换成无关解码器实现的原因。
占位类不声明 `setState`：运行时继承真实类的实现，其签名以 ANALYSIS.md §10.2 的
三 commit 比对为准。

同理，`VelocityServerConnection` 的真实类还有 `ensureConnected()`（`getConnection()`
的非空替代），而 VeloZip 用的是 `getConnection()` 加空值检查，因此占位类不声明它。
占位类只覆盖**实际调用面**，不等同于真实类的完整 API。

## 签名依据与验证历史

- **源码基线**：Velocity 3.4.0（commit `6b1ea78`，官方 build 566）；5 个占位类的
  javadoc 均标注该 commit。
- **跨版本比对**：`6b1ea78`（3.4.0）/ `4498f1e`（3.5.1）/ `db0a17e`（4.1.1）三个
  commit 中，`Connections` 常量、`BackendChannelInitializer` 安装顺序、
  `MinecraftVarintFrameDecoder` 构造器与 `setState(StateRegistry)` 签名完全一致
  （ANALYSIS.md §10.2）。
- **运行时实证**：v0.1.0–v1.0.0 的真实服务器 E2E 在 Velocity 3.4.0（build 563 /
  566）与 4.1.1（build 24）上完成了加载、协商与传输，执行过协商路径的
  `getConnectionInFlight`/`getConnection`/`getServer`/`getChannel`/`eventLoop`/
  `sendPluginMessage`，以及解码路径的构造器与 `decode`。`getConnectedServer()`
  是 in-flight 连接为空时的回退分支（见
  [VelocityNetworkAdapter](../velozip-velocity/src/main/java/dev/velozip/velocity/adapter/VelocityNetworkAdapter.java)），
  现有 E2E 记录不足以证明该分支被执行过。
  见 [E2E-1.0.0.zh-CN.md](E2E-1.0.0.zh-CN.md)、
  [E2E-0.3.0.zh-CN.md](E2E-0.3.0.zh-CN.md) 与
  [CHANGELOG.md](../CHANGELOG.md) 各版本 Verified 小节。

## 历史后端占位类（已在 v1.0.0 移除）

v0.1.0 阶段后端使用过 paper-server / NMS 编译占位。v1.0.0（提交 `af01606`）删除的
占位类共 7 个（均位于 `velozip-backend/src/stubs`），正是当时插件的完整连接链：

| 占位类 | 声明的内容 |
|---|---|
| `org.bukkit.craftbukkit.entity.CraftPlayer` | `getHandle()` |
| `net.minecraft.server.level.ServerPlayer` | `connection` 字段 |
| `net.minecraft.server.network.ServerGamePacketListenerImpl` | `connection` 字段 |
| `net.minecraft.network.Connection` | `channel` 字段 |
| `net.minecraft.network.Varint21FrameDecoder` | 构造器 `(BandwidthDebugMonitor)`、`decode` |
| `net.minecraft.network.BandwidthDebugMonitor` | 空标记类型（作为构造参数） |
| `io.papermc.paper.configuration.GlobalConfiguration` | `get()`、`proxies` 嵌套 |

按 [ANALYSIS.md §9](ANALYSIS.md) 第 4 条的计划，上述签名曾用 `javap` 对照真实
Purpur 26.1.2 服务端 jar 复核；[CHANGELOG.md](../CHANGELOG.md) 0.1.0 小节记录了
核验结论摘要，其中还包含随该次核验一并确认的 `HandlerNames` 常量名——常量不是占位
类，故不在上表内。`ChannelInitializeListener` 只出现在 [ANALYSIS.md §3.5](ANALYSIS.md)
的早期 hook 调研中，最终实现改用插件消息触发激活，仓库中从未有其占位类。

v1.0.0 起后端改为**不链接 NMS** 的实现：`PlayerChannels` 按已核验类型做受限反射
寻找唯一字段，`PaperForwarding` 运行时读取转发配置，全部 NMS 占位类随之移除。
shadowJar 仍保留 `net/minecraft/**`、`org/bukkit/craftbukkit/**`、
`io/papermc/paper/configuration/**` 的排除规则作为防线，`verifyArtifacts` 同样
检查成品 jar 不含这些前缀。

## 维护规则

- 新增或修改占位类时，必须先对照指定 commit 的真实源码核验签名，并在占位类
  javadoc 中标注来源 commit；禁止凭记忆或反编译结果猜测。
- 只声明 VeloZip 实际调用的成员；用不到的成员不进占位类。
- 修改后运行 `./gradlew build`，确认 `verifyArtifacts` 仍然通过。

## 当前验证边界

本文件引用的核验（源码比对、javap、真实 E2E）均为 v0.1.0–v1.0.0 期间的历史
证据。当前未发布的 1.16/1.17 兼容性扩展没有重新执行真实服务器 E2E（见
[TESTING-20260922.zh-CN.md](TESTING-20260922.zh-CN.md)），占位类未随当前 HEAD
重新核验；上述历史结论不自动适用于新扩展。
