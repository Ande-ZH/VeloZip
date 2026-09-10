# VeloZip 技术分析 — Velocity 3.4.0 ↔ Purpur 26.1.2 网络层

> 本文保留早期版本的源码分析。v1.0.0 已扩展到 1.18，后端不再继承 NMS 分帧类，
> 并延后移除原版压缩处理器以保护在途数据；当前实现与核验依据见 [v1.0.0 兼容说明](COMPATIBILITY-1.0.0.md)。

> 本文档是第一阶段（Phase 1 — Source Research / Phase 2 — Feasibility）的交付物。
> 所有结论均来自对真实源码/字节码的核验，不含凭记忆的猜测。每节标注来源。
> 未能在本阶段核验的点在 [§9 未核实清单](#9-未核实清单) 中明确列出。

## 目录

1. [版本与环境事实](#1-版本与环境事实)
2. [Velocity 3.4.0 网络分析](#2-velocity-340-网络分析)
3. [Purpur 26.1.2（Paper/Minecraft 服务端）网络分析](#3-purpur-2612paperminecraft-服务端网络分析)
4. [Packet 逐 handler 路径](#4-packet-逐-handler-路径)
5. [可行性结论与 Hook 点](#5-可行性结论与-hook-点)
6. [VeloZip 协议设计](#6-velozip-协议设计)
7. [时序安全性论证](#7-时序安全性论证)
8. [flush 语义与批处理设计](#8-flush-语义与批处理设计)
9. [未核实清单](#9-未核实清单)

---

## 1. 版本与环境事实

| 项 | 值 | 来源 |
|---|---|---|
| Velocity 3.4.0 | commit `6b1ea78ff77a7f060947a2597ad3a50274d71351`（"release 3.4.0"，2026-01-25），官方 build 566（STABLE） | GitHub PaperMC/Velocity `dev/3.0.0` 分支历史 + fill.papermc.io v3 |
| Velocity 依赖 | Netty **4.2.7.Final**、guava 25.1-jre、gson 2.10.1、Java toolchain 17 | `gradle/libs.versions.toml` / 根 `build.gradle.kts` @ 6b1ea78 |
| Purpur 26.1.2 | build 2592（2026-08），基于 Paper 26.1.2（PaperMC/Paper@70eaed65） | api.purpurmc.org/v2/purpur/26.1.2 |
| Minecraft 26.1.2 | "Tiny Takeover" 26.1 线 hotfix，协议版本 **775**；2026 年起 Mojang 弃用 `1.` 前缀改用 `year.drop.hotfix` | minecraft.wiki、minecraft.net 版本命名公告 |
| MC 26.1.2 Java 要求 | **Java 25**（piston-meta `java-runtime-epsilon`，major 25；实测 `Connection.class` 字节码 major 69） | piston-meta 26.1.2 JSON + jar 反汇编 |
| 服务端 Netty | 4.2.7.Final（vanilla libraries.list 全套） | 26.1.2 server.jar META-INF/libraries.list |
| 压缩实现（Velocity） | 优先 libdeflate 原生（linux x86_64/aarch64、musl、macos arm64），fallback Java Deflater | `native/.../util/Natives.java` @ 6b1ea78 |
| 压缩实现（Paper 26.1.2） | vanilla `java.util.zip.Deflater/Inflater`；Paper feature patch 0008 为 CompressionDecoder/Encoder 增加 VelocityCompressor 构造（类名不变） | PaperMC/Paper ver/26.1.2 `features/0008-*` |
| zstd-jni | 1.5.7-15（BSD-2 + native BSD-3/GPL2 双许可；**禁止 relocate**，JNI 依赖类全名） | maven central + github luben/zstd-jni README |

两端 Netty 版本完全一致（4.2.7.Final）——`velozip-common` 直接针对该版本编译，运行时零漂移。

## 2. Velocity 3.4.0 网络分析

### 2.1 Player Channel 与 Backend Channel 的创建

- **Player（客户端）连接**：`ServerChannelInitializer`（`com.velocitypowered.proxy.network`），server socket accept 时初始化；包含 LegacyPingDecoder/Encoder、可选 HAProxy 解码，`MinecraftConnection` 在 initializer 内加入。
- **Backend 连接**：`VelocityServerConnection.connect()`（`proxy/.../connection/backend/VelocityServerConnection.java`）：

```java
server.createBootstrap(proxyPlayer.getConnection().eventLoop())
    .handler(server.getBackendChannelInitializer())
    .connect(registeredServer.getServerInfo().getAddress())
    .addListener(future -> {
        connection = new MinecraftConnection(future.channel(), server);
        future.channel().pipeline().addLast(HANDLER, connection);
        connection.setActiveSessionHandler(StateRegistry.HANDSHAKE);
        ...
```

两个 initializer 完全独立（`ConnectionManager` 中分别持有）。backend 连接**复用玩家连接的 eventLoop**。
插件与 proxy 无类加载隔离（`PluginClassLoader` 的 parent 是 proxy classloader），可直接引用内部类。

### 2.2 Backend pipeline（初始，`BackendChannelInitializer.initChannel`）

按 `addLast` 顺序（head → tail）：

| # | handler 名（`Connections` 常量） | 类 | 方向/职责 |
|---|---|---|---|
| 1 | `frame-decoder` | `MinecraftVarintFrameDecoder`（ByteToMessageDecoder，有状态） | 入站：21-bit varint 长度 → 输出整帧 |
| 2 | `read-timeout` | `ReadTimeoutHandler`（默认 30s） | |
| 3 | `frame-encoder` | `MinecraftVarintLengthEncoder.INSTANCE`（@Sharable 单例） | 出站：varint 长度前缀 |
| 4 | `minecraft-decoder` | `MinecraftDecoder`(CLIENTBOUND) | 入站：packetId → Packet 对象；未注册 id 原样下传 |
| 5 | `flow-handler` | `AutoReadHolderHandler`（duplex） | autoRead 关闭时缓存入站消息 |
| 6 | `minecraft-encoder` | `MinecraftEncoder`(SERVERBOUND) | 出站：packetId + encode |
| 7 | `handler` | `MinecraftConnection`（连接建立时 addLast） | 会话处理、write/delayedWrite/flush |

**backend pipeline 无 FlushConsolidationHandler、无 cipher（backend 若 online-mode 直接抛异常，故永无加密 handler）。**

### 2.3 压缩何时/如何启用（backend 连接）

LOGIN 阶段后端发 Set Compression → `LoginSessionHandler.handle(SetCompressionPacket)` → `MinecraftConnection.setCompressionThreshold(threshold)`（源码 @ 6b1ea78）：

```java
if (threshold == -1) {
    channel.pipeline().remove(COMPRESSION_DECODER);
    channel.pipeline().remove(COMPRESSION_ENCODER);
    // 并 addBefore(MINECRAFT_DECODER, FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE)
} else {
    // 复用已存在则 setThreshold；否则：
    channel.pipeline().remove(FRAME_ENCODER);                    // 移除纯长度 encoder
    channel.pipeline().addBefore(MINECRAFT_DECODER, COMPRESSION_DECODER, new MinecraftCompressDecoder(...));
    channel.pipeline().addBefore(MINECRAFT_ENCODER, COMPRESSION_ENCODER, new MinecraftCompressorAndLengthEncoder(...));
    // fireUserEventTriggered(VelocityConnectionEvent.COMPRESSION_ENABLED)
}
```

要点：

- `MinecraftCompressEncoder` 在 3.4.0 **不存在**——出站是合并类 `MinecraftCompressorAndLengthEncoder`（压缩+varint 长度一次完成）。
- 启用压缩后出站路径：`minecraft-encoder → compression-encoder → head`（`frame-encoder` 已被移除）。
- **每次服务器切换 = 全新 TCP 连接 + 全新 pipeline**，Set Compression 每条连接独立重新处理；同一连接 PLAY↔CONFIG 往返不动压缩 handler。

### 2.4 关键源码事实（影响 VeloZip 设计）

1. **PLAY↔CONFIG 状态切换直接按类型引用帧解码器，无 null 检查**（`BackendPlaySessionHandler.handle(StartUpdatePacket)` 与 `ConfigSessionHandler.handle(FinishedUpdatePacket)`）：
   ```java
   smc.getChannel().pipeline().get(MinecraftVarintFrameDecoder.class).setState(StateRegistry.CONFIG);
   smc.getChannel().pipeline().get(MinecraftDecoder.class).setState(...);
   ```
   → **VeloZip 的双模解码器必须以 `MinecraftVarintFrameDecoder` 子类形态留在 `frame-decoder` 名位**（子类满足 `get(class)` 匹配，`setState` 继续工作）。
2. `MinecraftVarintFrameDecoder` 对前导 `0x00` 的行为：`in.forEachByte(FIND_NON_NUL)` 主动**跳过 0x00 字节串**；`length == 0` 的帧被静默忽略；非法 varint（>21-bit）抛 `QuietDecoderException` → 连接关闭。**合法 vanilla 流的帧边界首字节不可能为 0x00**（varint 0x00 只能编码 length 0，而 length 0 不是合法帧）。
3. 解压上限在 `MinecraftCompressDecoder`：`UNCOMPRESSED_CAP` 默认 8 MiB（系统属性可调）。
4. `MinecraftConnection.write()` = `channel.writeAndFlush`（每包即刷）；`delayedWrite()` 只写不刷。
5. `VelocityCompressor.close()`：libdeflate 实现有 `disposed` 守卫（幂等）；Java 实现依赖 JDK `Deflater.end()/Inflater.end()` 的内部防重。**移除共享同一 compressor 的两个压缩 handler（两者 handlerRemoved 都会 close）是安全的。**
6. 拿 backend Channel 的调用链（插件可直接调用）：
   ```java
   ConnectedPlayer cp = (ConnectedPlayer) player;
   VelocityServerConnection sc = cp.getConnectedServer();      // 或 getConnectionInFlight()
   MinecraftConnection mc = sc.ensureConnected();              // getConnection() 为 @Nullable 版
   Channel ch = mc.getChannel();  EventLoop loop = mc.eventLoop();
   ```
7. modern forwarding 的 login plugin channel 常量为 `PlayerDataForwarding.CHANNEL = "velocity:player_info"`。
8. 插件事件面：`ServerConnectedEvent` 在 **PLAY 阶段**（`TransitionSessionHandler.handle(JoinGamePacket)`，事件期间 `smc.setAutoReading(false)`，处理必须快）；`ServerConnection.sendPluginMessage(ChannelIdentifier, byte[]|ByteBuf)`；拦截后端→客户端 plugin message 用 `PluginMessageEvent`（`com.velocitypowered.api.event.connection`，`setResult(ForwardResult.handled())` 阻止转发；**仅对 ChannelRegistrar 注册过的 channel 触发事件**，未注册 channel 的消息不触发事件、直接转发）。

## 3. Purpur 26.1.2（Paper/Minecraft 服务端）网络分析

### 3.1 连接建立与 pipeline

`ServerConnectionListener$1.initChannel`（vanilla 26.1.2 字节码 trace + Paper 补丁 0003 交叉验证），head → tail：

| # | handler 名（`HandlerNames` 常量，已从 `HandlerNames.class` 常量池提取） | 类 | 说明 |
|---|---|---|---|
| 0 | （无名，addFirst） | `FlushConsolidationHandler` | Paper 默认启用；`-DPaper.disableFlushConsolidate` 关闭 |
| 1 | `timeout` | `ReadTimeoutHandler(30)` | |
| 2 | `legacy_query` | `LegacyQueryHandler` | 仅 status 连接 |
| 3 | `splitter` | `Varint21FrameDecoder`（TCP） | createFrameDecoder；本地连接为 LocalFrameDecoder |
| 4 | （无名） | `UnconfiguredPipelineHandler$Inbound` + `FlowControlHandler` | |
| 5 | `decoder`（或 `inbound_config`） | `PacketDecoder` | |
| 6 | `prepender` | `Varint21LengthFieldPrepender`（TCP） | createFrameEncoder |
| 7 | `encoder`（或 `outbound_config`） | `PacketEncoder` | |
| 8 | `packet_handler` | `Connection` | 之后调用 `ChannelInitializeListenerHolder.callListeners(channel)` |

### 3.2 压缩

`Connection.setupCompression(int threshold, boolean validateDecompressed)`（字节码逐指令还原）：

```
threshold >= 0:
    get("decompress") instanceof CompressionDecoder ? setThreshold(t, validate)
      : addAfter("splitter", "decompress", new CompressionDecoder(t, validate));
    get("compress")   instanceof CompressionEncoder ? setThreshold(t)
      : addAfter("prepender", "compress", new CompressionEncoder(t));
threshold < 0:
    remove("decompress"); remove("compress");
```

- 启用时机：`ServerLoginPacketListenerImpl.verifyLoginAndFinishConnectionSetup` → `send(new ClientboundLoginCompressionPacket(t), listener.thenRun(() -> connection.setupCompression(t, true)))`；Paper 改为保证 Set Compression 写入与压缩启用在同一 eventLoop 任务（修 MC-308621）。
- `network-compression-threshold = -1` → 不加压缩 handler。
- Paper 在 setupCompression 末尾 `fireUserEventTriggered(ConnectionEvent.COMPRESSION_THRESHOLD_SET / COMPRESSION_DISABLED)`。
- **全 jar 扫描：`setupCompression` 只被 Connection 与 ServerLoginPacketListenerImpl 引用**；CONFIGURATION↔PLAY 切换不触碰 compress/decompress。

### 3.3 协议状态切换对 pipeline 的影响

`Connection.setupInboundProtocol/setupOutboundProtocol`（经 UnconfiguredPipelineHandler 的 ConfigurationTask 在 eventLoop 上执行）：

- `pipeline.replace(ctx.name(), "decoder", handler)` / `replace(ctx.name(), "encoder", handler)`（按名字 replace）
- `addAfter("decoder", "bundler", PacketBundlePacker)` / `addAfter("encoder", "unbundler", PacketBundleUnpacker)`（bundle 处理完自移除）

**结论：状态切换只 replace `decoder`/`encoder` 名位、以这两个名字为锚加 bundler/unbundler；`splitter`/`prepender`/`compress`/`decompress` 的名字与位置不被触碰，26.1.2 无对它们的 instanceof 检查** → 在这些名位上做运行期替换是安全的（我们仍让双模解码器继承 `Varint21FrameDecoder`，零成本对冲未来版本行为变化）。

### 3.4 flush / 出站批量

- Paper 删除了 vanilla 每 tick 的显式 `channel.flush()`（Connection.tick() 补丁，`-Dpaper.explicit-flush` 恢复）。
- 出站优化 = `FlushConsolidationHandler`（addFirst）+ pendingActions 队列。**PacketBatcher 不存在于 26.1.2**（bundle 用 PacketBundlePacker/Unpacker）。
- `CraftPlayer.sendPluginMessage` 走 `ClientboundCustomPayloadPacket` → 标准出站 pipeline → Velocity 原样收到。

### 3.5 插件 hook 面（26.1.2 实测存在）

- `io.papermc.paper.network.ChannelInitializeListener`：`@FunctionalInterface void afterInitChannel(Channel)`；注册用 `ChannelInitializeListenerHolder.addListener(adventure Key, listener)`，**在每个新 TCP channel 的全部 minecraft handler 添加完成之后**调用（含 ping 连接）。位于 paper-server（不在 paper-api javadoc 中）→ 以 compileOnly stub 方式引用。
- Bukkit `Messenger#registerIncomingPluginChannel(plugin, "velozip:negotiate", listener)`（channel 名规则：含 `:`、全小写 → `velozip:negotiate` 合法）；**PluginMessageListener 在主线程被调用**（Paper PacketProcessor 入队 → 主线程 processQueuedPackets）。
- modern forwarding 识别：无公开玩家级 API；`GlobalConfiguration.get().proxies.velocity.enabled`（paper-global.yml）可判定服务端是否启用 Velocity 转发校验——启用时直连无法通过登录 HMAC，故成功登录的连接必然经过持有 forwarding 密钥的代理。

## 4. Packet 逐 handler 路径

### 4.1 Velocity → Purpur（backend 连接 serverbound）

```
MinecraftConnection.write/delayedWrite (handler)
  → flow-handler (AutoReadHolderHandler)
  → [play-packet-queue-outbound — 仅 CONFIG 阶段存在]
  → minecraft-encoder (MinecraftEncoder: packet → packetId varint + body)
  → compression-encoder (MinecraftCompressorAndLengthEncoder: zlib + [varint 总长][varint 解压长][deflate])
     （未启用压缩时为 frame-encoder: MinecraftVarintLengthEncoder [varint 总长][body]）
  → socket
```

Purpur 侧接收：

```
socket
  → FlushConsolidationHandler
  → timeout (ReadTimeoutHandler)
  → splitter (Varint21FrameDecoder: [varint 总长] → 整帧)
  → decompress (CompressionDecoder: 解 zlib → [varint 解压长]剥离)
  → FlowControlHandler / UnconfiguredPipelineHandler$Inbound
  → decoder (PacketDecoder: packetId → Packet 对象)
  → packet_handler (Connection → 对应 PacketListener)
```

### 4.2 Purpur → Velocity（backend 连接 clientbound）

```
Connection.send (packet_handler)
  → encoder (PacketEncoder: packet → bytes)
  → compress (CompressionEncoder: [varint 解压长][deflate])
  → prepender (Varint21LengthFieldPrepender: [varint 总长])
  → timeout → FlushConsolidationHandler → socket
```

Velocity 侧接收：

```
socket
  → frame-decoder (MinecraftVarintFrameDecoder → 整帧)
  → read-timeout
  → compression-decoder (MinecraftCompressDecoder: 解 zlib)
  → minecraft-decoder (MinecraftDecoder: packetId → Packet / 未知 id 原样下传)
  → flow-handler
  → handler (MinecraftConnection → SessionHandler；未知包由 BackendPlaySessionHandler.delayedWrite 原样转给客户端)
```

## 5. 可行性结论与 Hook 点

**结论：可以在现有 backend TCP 连接上安全切换为 VeloZip framing，不需要第二条业务连接，不破坏协议状态机。** 依据：

1. 协商发生在 PLAY 状态的 plugin message 通道上（`velozip:negotiate`），Login/握手阶段零改动；
2. 切换只替换 transport 层名位（`frame-decoder`/`splitter`、压缩 handler、`frame-encoder`/`prepender`），packet 编解码层（`minecraft-*`/`decoder`/`encoder`）原样保留，状态切换代码不感知；
3. 类型引用约束（Velocity 的 `get(MinecraftVarintFrameDecoder.class)`）用子类满足；
4. 帧边界 0x00 magic 无歧义（§2.4-2）；
5. TCP 有序 + eventLoop 单线程 FIFO 消除切换竞态（§7）。

具体注入点：

| 端 | 入站 | 出站 |
|---|---|---|
| Velocity（仅 backend channel） | `frame-decoder` → 双模解码器（`MinecraftVarintFrameDecoder` 子类）；移除 `compression-decoder` | 移除 `compression-encoder`（或未压缩时的 `frame-encoder`）；`addBefore("minecraft-encoder", "velozip-encoder", ...)` |
| Purpur | `splitter` → 双模解码器（`Varint21FrameDecoder` 子类）；移除 `decompress` | 移除 `compress`、`prepender`；`addBefore("encoder", "velozip-encoder", ...)` |

双模解码器在 vanilla 模式下完全复用父类逻辑（透传给 `super.decode`），只在**帧边界**检查首字节；检测到 0x00 magic 后永久进入 VeloZip 模式。

**客户端链路隔离**：所有注入入口都以 Velocity `ServerConnectedEvent` 拿到的 backend `ServerConnection` 为起点（或 Purpur 端 `ChannelInitializeListener` 按名位过滤后的 channel），物理上不可能触碰 player channel；客户端的 compression threshold / zlib / 加密 / framing / ViaVersion 路径零改动。

## 6. VeloZip 协议设计

（实现细节见代码；此处为规范。）

### 6.1 帧格式

```
VeloZip Frame:
  0x00 0x5A          magic（帧边界上 vanilla 不可能出现 0x00 开头，见 §2.4-2）
  ver   (u8)         Transport Protocol Version = 1
  flags (u8)         0x01 = ZSTD, 0x02 = RAW
  varint payloadLen  压缩后（或 RAW 时原始）字节数
  varint origLen     仅 ZSTD：原始字节数（用于精确分配解压缓冲）
  payload            payloadLen 字节
payload = 一个或多个**完整** Minecraft varint 帧的顺序拼接（batch）
```

- 解析规则：先读 header，验证 `payloadLen <= max-frame-size`（默认 1 MiB）、`origLen <= max-uncompressed-size`（默认 2 MiB）**之后**才允许分配；ZSTD 解压失败、magic/flags/长度非法 → 关闭该连接并记录原因（绝不无上限 allocate）。
- 默认上限论证：常规 batch ≤ 64 KiB；单包超 64 KiB 时独立成帧。现代 chunk 发送被拆分为多个较小包，单包超过 2 MiB 的场景在 vanilla/常规插件流量中不存在；若出现（异常巨型包）→ 记录明确日志并关闭连接，用户可调大 limits。上限同时充当 zstd 解压炸弹防护（≤ max-uncompressed-size 的直接分配上限 + 解压结果长度必须等于 origLen 的强校验）。

### 6.2 RAW 条件

满足任一即发 RAW 帧（不压缩）：

1. batch 载荷 < `compression.raw-threshold`（默认 128 B）；
2. 压缩后大小 ≥ 原始大小（压缩无收益；代价是先压后弃的一次 direct 拷贝，benchmark 会给出该策略的收益数据）。

### 6.3 协商（Handshake）

channel `velozip:negotiate`（MinecraftChannelIdentifier("velozip","negotiate")；两端必须一致）。

```
REQ  (Velocity → Purpur, plugin message)：
  u8 msgType=0x01
  u8 transportProto=1, u8 algo=1(zstd), u8 level=1
  16B random nonce
  [若两端配置 secret 非空] 32B HMAC-SHA256(secret, nonce || transportProto || algo || level)
  varint pluginVersionLen + utf8（仅用于日志展示）

REFUSE (Purpur → Velocity, plugin message, 走 vanilla 路径)：
  u8 msgType=0x02
  u8 reasonCode + utf8 reason（如 PROTO_MISMATCH/AUTH_FAILED/DISABLED/PARAM_MISMATCH）
  Velocity 侧注册 velozip channel 并以 PluginMessageEvent.setResult(handled()) 拦截，绝不转发给真实客户端。
```

- 认证目标（提示词 §15）：防"错误服务器/错误配置意外协商成功"+ 防重放（nonce 每次随机），不是 TLS。
- secret 规则：两端都空 → 跳过认证；一端空一端非空 → AUTH_FAILED 拒绝。
- 协议版本独立于插件版本：`TransportProtocol = 1` 常量；两端只要求 protoVer 相等，不比较 pluginVersion。
- 成功路径无需显式 ACK：Purpur 切换后发出的**第一个 VeloZip 帧（0x00 0x5A magic）即事实 ACK**——Velocity 的双模解码器在 pipeline 层检测到 magic 时切换本端出站（见 §7 时序）。
- Velocity 侧 5s 未收到 magic/REFUSE → 视为对端无 VeloZip → 还原 vanilla 解码器实例，按 `require-velozip` 处理（false=静默 fallback，true=断开该后端连接 + 明确日志；同时缓存该服务器失败状态供 `ServerPreConnectEvent` 直接拒绝）。

## 7. 时序安全性论证

所有 pipeline 变更都在目标 channel 的 eventLoop 上以单任务原子完成；Netty 保证同一 channel 的所有操作 FIFO 串行；TCP 保证字节流有序。设 V=Velocity 插件，B=Backend 插件：

1. V 在 eventLoop 任务 T1 内：`frame-decoder` → 双模解码器；发送 REQ（vanilla plugin message）。⇒ REQ 是 T1 之后的第一批出站字节，B 以 vanilla 方式收到。
2. B 的 PluginMessageListener（主线程）校验通过 → 在该 channel eventLoop 任务 T2 内：装双模解码器、移除 compress/decompress/prepender、装 velozip-encoder。⇒ T2 之后 B 的所有出站字节均为 VeloZip 帧；B 的入站解析从 T2 起接受两种帧（双模）。
3. B 发出的第一个 VeloZip 帧到达 V：V 的双模解码器（T1 已就位）在帧边界检测到 magic → **在同一 eventLoop 任务内**切换 V 出站（移除 compression-encoder 等、装 velozip-encoder）。
4. V 的第一个 VeloZip 帧到达 B 时，B 的双模解码器已在 T2 安装（T2 因果先于 B 的首帧，而 V 的首帧因果上晚于收到 B 的首帧）。

不存在"一端已切 VeloZip framing、另一端仍按 vanilla 解析"的窗口：

- V→B 方向：B 的双模解码器安装（T2）严格先于 V 可能发出任何 VeloZip 帧（V 只在收到 B 的 magic 帧后才切换出站）。
- B→V 方向：V 的双模解码器安装（T1）严格先于 REQ 发出，而 B 的首帧严格晚于 REQ。
- 中途 vanilla 帧（如 REFUSE、协商期间的正常游戏包）：双模解码器 vanilla 模式 = 父类原逻辑，逐帧解析，帧边界检查不影响 vanilla 帧内容。
- 协商失败/超时：B 从未安装任何东西（vanilla 原样）；V 还原原 vanilla 解码器实例（事件 `channelRead` 期间无中间状态，还原任务在 eventLoop FIFO 语义下与其余操作串行）。

## 8. flush 语义与批处理设计

实测的 flush 行为（§2.4-4、§3.4）：

- Velocity backend 出站：`MinecraftConnection.write()` 每包 `writeAndFlush`；
- Paper 出站：每 send 伴随 flush 调用，真实刷出由 FlushConsolidationHandler 聚合。

⇒ 若 VeloZip 编码器把"上游 flush()"直接当作发送信号，batch 将退化为 1 个包/帧（尤其 Velocity 侧），批处理失效。因此设计为：

- `write()`：累计到 pooled direct 累积缓冲；≥ `batch.max-size`（默认 64 KiB）立即 emit；批内首帧到达时启动 `batch.max-delay-micros`（默认 500 µs）eventLoop 定时器；
- `flush()`：记 pendingFlush，不立即下传（额外延迟上界 = 500 µs，远低于 MC tick 50 ms）；
- emit（定时器到期 / 大小上限）：累积 → 单个 direct ByteBuf → `ZstdCompressCtx(level=1)`（per-channel，eventLoop 单线程满足其非线程安全约束）→ 帧头 + `ctx.writeAndFlush`；
- 空 batch 的 flush 直接下传；channelInactive/handlerRemoved 取消定时器、释放全部缓冲、close zstd ctx。

该设计以 Netty flush 为"窗口结束信号之一"（pendingFlush 保证上游语义不丢失），窗口聚合保证批量效果；延迟预算（平均与 P99 < 1 ms 目标）由 M5 benchmark 验证。压缩内联在 eventLoop 执行（zstd-1 native + ≤64 KiB 预计单次 <100 µs），仅当数据证明 P99 阻塞明显才引入专用 executor（第一阶段不引入）。

## 9. 未核实清单

1. `inboundHandlerName(z)/outboundHandlerName(z)` 的分支方向（影响极小：登录完成后稳定名字为 `decoder`/`encoder`，已由 replace 目标名证实）。
2. Purpur 自身补丁是否触碰网络层（网络层结论继承 Paper ver/26.1.2；M4 以真实 Purpur build 2592 集成测试兜底）。
3. Bukkit 端 incoming plugin message 派发是否要求对端先行 `minecraft:register` 声明（现行证据：incoming 派发只依赖服务端插件注册；M4 实测验证，若需要则启用 Velocity ChannelRegistrar 注册公告作为通道声明，代码已预留）。
4. 纯 paper-api 依赖下 stub 引用 paper-server 内部类的签名正确性 → M0/M3 从真实 26.1.2 服务端 jar 以 javap 复核（见 docs/STUBS.md）。
5. Velocity 3.4.0 在 Java 25 JVM 上的运行兼容性（M4 本地实测）。

## 10. v0.2.0 版本扩展调研附录（2026-08-30）

本附录记录 v0.2.0 将支持区间从「仅 Velocity 3.4.0 ↔ Purpur 26.1.2」扩展到
「Velocity 3.4.0–4.1.1 ↔ Purpur 26.1.2/26.2」的全部核验证据。所有结论均来自对
fill.papermc.io v3 / repo.papermc.io / GitHub 源码（指定 commit）的核验，非记忆推测。

### 10.1 版本与协议注册表

| 平台 | 版本 | 发布 | 最新 build | 协议范围 | Java | 状态 |
|---|---|---|---|---|---|---|
| Velocity | 3.4.0 | 2026-01-25 | 566（commit 6b1ea78） | 4 → 774 (1.21.11) | 17 | UNSUPPORTED（2026-08-24 起） |
| Velocity | 3.5.0 / 3.5.1 | 2026-07-11 | 615（commit 4498f1e） | 4 → **776 (26.2)** | 21 | 3.5.1 DEPRECATED（2026-09-30 止） |
| Velocity | 4.0.0 | 2026-07-14 | 6（commit 90f8905） | 4 → 776 | 25 | UNSUPPORTED |
| Velocity | 4.1.0 / 4.1.1 | 2026-08-24 / 08-26 | 24（commit db0a17e） | 4 → 776 | 25 | **SUPPORTED（推荐）** |
| Paper/Purpur | 26.1.2 | — | Paper 74 / Purpur 2592 | 775 | 25 | 稳定 |
| Paper/Purpur | 26.2 | 2026-08-29 / 08-26 | Paper 121 / Purpur 2627 | 776 | 25 | 稳定 |

- 26.1（775）于 2026-03-22 由 PR #1739 并入 Velocity（3.5.0 起可用）；26.2（776）
  于 2026-06-16 由 PR #1807 并入。26.3 仅为开放草案 PR #1867，未并入。
- 客户端范围完全由代理协议注册表决定；VeloZip 代码零协议版本引用（v0.1.0 即如此）。

### 10.2 Velocity 网络层 3.4.0 → 4.1.1 稳定性核验

对 6b1ea78（3.4.0）、4498f1e（3.5.1）、db0a17e（4.1.1）三个 commit 逐一比对：

- `Connections` 常量（`frame-decoder`/`frame-encoder`/`compression-decoder`/
  `compression-encoder`/`minecraft-decoder`/`minecraft-encoder` 等 15 个）**完全一致**。
- `BackendChannelInitializer` 安装顺序一致：frame-decoder → read-timeout →
  frame-encoder → minecraft-decoder → flow-handler → minecraft-encoder。
- `MinecraftVarintFrameDecoder`（`proxy.protocol.netty` 包，构造器
  `(ProtocolUtils.Direction)`、`setState(StateRegistry)`）三版本签名一致。
  `MinecraftConnection.setState` 通过**按类查找 + 空检查**取 frame decoder 并调用
  `setState`——VeloZip 子类替换满足该查找（子类实例匹配父类查找）。
- `VelocityServerConnection.sendPluginMessage(ChannelIdentifier, byte[])` 一致；
  `ServerConnectedEvent` 在 `TransitionSessionHandler.handle(JoinGamePacket)`、
  `setAutoReading(false)` 期间触发的时序一致；`getConnectionInFlight()` 存在。
- **4.x 差异**：`MinecraftConnection` 移至 `proxy.connection` 包（3.4.0 实测即此包，
  与 4.x 一致——插件引用无差异）；Java 25 + Adventure 5.2.0。
  Adventure 5.0 曾移除 `BuildableComponent`（对 4.x 编译插件抛 NoSuchMethodError），
  **5.2.0 恢复桥接方法**修复了该二进制兼容问题；Velocity 4.1.1 恰好携带 5.2.0。
  VeloZip 仅用 `Component.text`/`NamedTextColor`/builder——经 5.2.0 javadoc 逐一
  核验签名不变（`NamedTextColor` 由枚举改为 final 类，但静态字段读取的二进制
  描述符不变）。
- 3.5.x/4.x 新增 serverbound `SimpleBytesPerSecondLimiter`（解压后限速）与
  「压缩声明校验」——均作用于 packet 层，与 VeloZip 的 transport 层替换正交
  （组合 B/C/D 实测共存正常）。

### 10.3 Paper/Purpur 网络层 26.1.2 → 26.2 稳定性核验

对 Paper `ver/26.1.2` 与 `main`（26.2）的 patch 集比对（Purpur 自身 delta 仅
连接限速/断开消息/AFK，不触碰网络层）：

- vanilla `HandlerNames` 未被 patch；`Connection.java.patch` 与 0007 功能 patch
  仅引用 SPLITTER/PREPENDER/DECOMPRESS/COMPRESS，两版本一致；bundler/unbundler
  已不存在。
- `Varint21FrameDecoder` 两版本 patch 逐字节一致（仅 decode 顶部的
  `channel().isActive()` 守卫）；构造器 `(BandwidthDebugMonitor)`（可空）不变，
  `super(null)` 子类化仍可编译。
- `Connection.channel` 公有字段不变（访问转换器条目仍在）；`ServerPlayer.connection`
  → `ServerGamePacketListenerImpl.connection` 链不变；`CraftPlayer.getHandle()`
  在无版本号包（1.20.5+ 起不变）。
- 26.2 唯一真实网络变化：登录压缩时序修复（#13929 + c9e894d，
  `sendPacketAndScheduleNettyTask`）——只影响登录包写入顺序，VeloZip 在 PLAY 态
  协商后才移除压缩 handler，正交（组合 C 实测通过）。

### 10.4 E2E 实测矩阵结论

见 docs/BENCHMARK.md「v0.2.0 client-range E2E matrix」：四组合全部双端激活，
带宽降低 79.2–83.4%。偶发与上游问题定性记录于 CHANGELOG 0.2.0 Notes。
