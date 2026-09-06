# VeloZip v0.3.0 全量 JMH 实测报告（中文）

## 结论与测量日期

本报告为 **2026-09-07 新执行**的结果，不是旧数据改写。执行时间为北京时间 04:25:24–04:42:56（UTC 2026-09-06 20:25:24–20:42:56）；JMH 自报耗时 17 分 16 秒，Gradle 构建与执行约 17 分 30 秒。

完整执行现有套件的 **38 个参数组合、76 个 fork、608 个测量迭代均值**，无筛选、无失败样本剔除。构建和运行退出码均为 0。现有套件覆盖孤立 Zstd 压缩、压缩+解压，以及预压缩批数据的解压；**不覆盖 RAW 帧路径、真正的批处理编解码往返或网络 E2E**。

关键结果：

- MC_LIKE 压缩：32 KiB 为 **27.608 ± 2.683 µs/op**；64 KiB 为 **80.098 ± 23.777 µs/op**。64 KiB 该项波动较大，不应把均值比值当成稳定的扩容规律。
- MC_LIKE 压缩+解压（包括每次分配/释放非池化解压输出）：32 KiB 为 **45.531 ± 1.803 µs/op**；64 KiB 为 **99.227 ± 3.903 µs/op**。
- 重复字母批数据、预分配输出的**仅解压**：32 KiB 为 **9.705 ± 0.086 µs/op**；64 KiB 为 **19.546 ± 1.830 µs/op**。本次两者置信区间不重叠，不能沿用旧文档“不可区分”的说法；但这不是生产批处理性能结论。
- 不能由这些微基准宣称带宽节省、TPS 提升、P99 延迟达标或事件循环承载能力，也不据此修改默认批大小。

## 1. 环境、版本与产物

| 项目 | 实际值 |
|---|---|
| Git commit | 291f7c4dcad5a7bf1751834d001d82b5a1b2e359 |
| Gradle 项目版本 | 0.3.0 |
| Wrapper | Gradle 9.7.1；仓库 wrapper 调用，未使用共享 gradle 默认命令 |
| JDK | /env/zulu25；Zulu25.36+205-CA，25.0.4.1+1-LTS |
| JMH | 1.37；compiler blackhole 自动检测 |
| Zstd JNI / 压缩级别 | 1.5.7-15 / level 1 |
| Netty | 4.2.7.Final |
| CPU | Intel Xeon Processor (Skylake, IBRS)，2 vCPU，1 socket × 2 cores，无 SMT |
| 虚拟化 | Microsoft hypervisor；共享运行环境，未绑核、未隔离后台负载 |
| OS | Linux 6.12.94+deb13-cloud-amd64，x86_64 |
| 内存 | 主机约 7.8 GiB，无 swap；开始时 available 约 2.5 GiB |
| Java 编译目标 | Java 17 字节码，JDK 25 编译与执行 |

通过 --rerun-tasks 重新编译 common 与 benchmark 并构建 0.3.0 JAR；JMH JavaExec 使用 main runtimeClasspath（本次生成的 benchmark classes 和依赖，包括 common JAR），不是复用旧 0.2.0 benchmark JAR，也不是测量发行插件全链路。

产物 SHA-256：

```text
61efebdca3251219bdb998c9c95bbdfa66ce5ff41123245bd29d3adbb62246ac  /project/Velozip/velozip-benchmark/build/libs/velozip-benchmark-0.3.0.jar
06eb701dd4016a34d5b9a4c25fb614c44fc40305df9a1539a7577cc8d35f191c  /project/Velozip/velozip-common/build/libs/velozip-common-0.3.0.jar
d539676c48b596afda64c963ec8f7ee56c7b3fe7e3b81d1dbe2d1a1e3dd9e9f8  /project/Velozip/gradlew.bat
```

执行前唯一已有工作区修改为 gradlew.bat；前后 SHA-256 相同。未修改 Java 源码、Gradle 配置或已有文档；仅新增本报告以及 build 下的执行脚本、结果与核验文件。未提交、推送，未调整共享环境默认值，未操作 /project/mc 的服务或文件。

## 2. 方法与资源边界

- 全量参数：compressOnly 与 ctxRoundTrip 各 3 数据集 × 6 大小 = 18 组；batchRoundTrip 为 2 个批大小。
- 单线程；每组 **2 个独立 JVM fork**；每 fork 预热 **5 × 1 秒**，测量 **8 × 1 秒**。每组有 16 个迭代均值，不是 16 次单独操作。
- AverageTime 模式，单位 µs/op，数值越低越好。表中 ± 是 JMH 给出的 **99.9% 置信区间半宽**（scoreError），不是标准差，也不是单次请求的 P99。原始 JSON 包含 scoreConfidence 和每 fork 的 rawData。
- JVM 参数：-Xms128m -Xmx384m -XX:MaxDirectMemorySize=128m -XX:ActiveProcessorCount=2 -XX:+UseG1GC -Dio.netty.leakDetection.level=simple。
- Gradle 单 worker、禁用 parallel、一次性 daemon 最大堆 384 MiB；fork 顺序运行。JMH launcher 未另设堆上限，实际负载集中在上述受限 fork。未启动并行压测或 profiler。
- 这比源码注解默认的 1 fork、3 次预热、5 次测量更充分；没有降低为 smoke counts。未对波动大的样本择优重跑。

## 3. 源码语义核对：名称不是实际覆盖范围

源文件：/project/Velozip/velozip-benchmark/src/main/java/dev/velozip/benchmark/CompressionBenchmark.java。

| 方法 | 计时区实际执行 | 不应声称的能力 |
|---|---|---|
| compressOnly | 复用 ZstdCompressCtx、输入和输出 direct ByteBuf，生成 NIO 视图并压缩 | 非完整 production outbound，未含阈值判断、RAW 回退、帧封装、批聚合与指标 |
| ctxRoundTrip | 压缩后解压；每操作分配一个 Unpooled direct 输出，随后 release | 非网络往返、非真实 handler pipeline；不能简单减去 compressOnly 得出纯解压耗时 |
| BatchRoundTrip.batchRoundTrip | setup 中预压缩；计时仅调用 ZstdDecompressor.decompress，复用 direct 输入输出，并消费返回长度 | **非压缩+解压**；未调用 encoder/decoder handler、解析预构建 frame、处理多包或触发定时 flush |

所有基准 direct ByteBuf 都由 **Unpooled** 创建，旧注释和旧文档的“pooled throughout”不准确。batchRoundTrip 计时区没有每操作分配新的 ByteBuf，不能称它是“输出缓冲分配主导”。

数据集固定使用 Random(0xC0DE)：RANDOM 是伪随机字节；COMPRESSIBLE 是 a–h 的八符号数据，不是单一重复字符；MC_LIKE 每最多 4096 字节选择重复 ID、稀疏 metadata、JSON 或轻噪声，**是合成样本，不是抓包回放**。小尺寸不一定包含完整混合形态。batchRoundTrip 独立使用 a–z 周期重复正文，加一个 Minecraft VarInt 长度前缀，总计 32768/65536 B，不是 MC_LIKE 或真实多包批次。

batch setup 创建 rawThreshold=0、batchMaxSize=batchSize 配置及 metrics，但计时未使用配置驱动 encoder。生产 RAW 条件存在于 /project/Velozip/velozip-common/src/main/java/dev/velozip/common/transport/VeloZipBatchEncoder.java：小于 rawThreshold 直接 RAW，或压缩后无收益/超过帧限制回退 RAW。现有 JMH 不调用这些分支；**RANDOM 只能代表难压缩输入的 Zstd 成本，RAW 吞吐/延迟本次未测**。不为此新增实现或更改基准代码。

## 4. 新实测完整结果

以下各单元格均为平均值 ± 99.9% 置信区间半宽，单位 **µs/op**；每格 n=16（2 fork × 8 测量迭代）。

### 4.1 仅压缩：compressOnly

| 输入大小 | RANDOM | COMPRESSIBLE | MC_LIKE |
|---:|---:|---:|---:|
| 128 B | 1.014 ± 0.037 | 2.707 ± 0.070 | 1.107 ± 0.031 |
| 1024 B | 2.376 ± 0.117 | 6.809 ± 0.299 | 4.941 ± 0.236 |
| 4096 B | 4.833 ± 0.211 | 18.744 ± 0.802 | 10.881 ± 0.296 |
| 16384 B | 11.954 ± 0.474 | 87.436 ± 2.918 | 18.447 ± 0.609 |
| 32768 B | 20.051 ± 0.574 | 113.486 ± 4.364 | 27.608 ± 2.683 |
| 65536 B | 15.934 ± 0.564 | 244.003 ± 9.475 | 80.098 ± 23.777 |

### 4.2 压缩+解压：ctxRoundTrip

包含非池化解压输出的每次分配/释放，不能代表无分配往返。

| 输入大小 | RANDOM | COMPRESSIBLE | MC_LIKE |
|---:|---:|---:|---:|
| 128 B | 1.770 ± 0.553 | 4.437 ± 0.155 | 1.708 ± 0.060 |
| 1024 B | 2.710 ± 0.072 | 9.089 ± 0.244 | 7.726 ± 0.213 |
| 4096 B | 5.406 ± 0.188 | 25.251 ± 0.960 | 17.934 ± 0.336 |
| 16384 B | 12.908 ± 0.396 | 119.611 ± 5.326 | 31.197 ± 0.590 |
| 32768 B | 22.001 ± 0.795 | 140.040 ± 3.835 | 45.531 ± 1.803 |
| 65536 B | 18.642 ± 0.687 | 303.585 ± 11.936 | 99.227 ± 3.903 |

### 4.3 预压缩批数据，仅解压：batchRoundTrip

| batchSize | 平均耗时 ± 99.9% 误差半宽（µs/op） | 99.9% 置信区间（µs/op） |
|---:|---:|---:|
| 32768 B | 9.705 ± 0.086 | [9.620, 9.791] |
| 65536 B | 19.546 ± 1.830 | [17.716, 21.376] |

## 5. 波动、告警与局限

- MC_LIKE 64 KiB compressOnly 的误差半宽约为均值的 29.7%；RANDOM 128 B ctxRoundTrip 约为 31.2%。原始样本与两 fork 均值核验记录在 validation.json，未掩盖波动。共享 VM、后台任务、GC/JIT 等都可能影响结果；本次没有 profiler，不能归因到其中某一因素。
- RANDOM 64 KiB 压缩均值低于 32 KiB，不能假设耗时随大小严格单调，也不能仅凭本次结果归因具体压缩算法分支。
- 完整日志未发现 LEAK:、OutOfMemoryError、Exception、FAILED 或 JMH <failure>；38 组均完整，构建成功。存在 JMH 使用 sun.misc.Unsafe 的终止弃用警告、zstd-jni System.loadLibrary 的 restricted native-access 警告，另有既有 YamlConfigs unchecked 编译提示。警告未导致本次失败，保留原始日志而非隐藏。
- **没有泄漏告警不等于没有生命周期问题**：现有 benchmark 没有 @TearDown，setup 持有的 direct ByteBuf、组合帧和上下文未显式 release/close；每 fork 退出回收进程资源，但本次不是长期泄漏验证。simple 为采样检测，不是 PARANOID。ctxRoundTrip 每次创建的输出确实 release。为保持现有基准语义，本次未修代码。
- 完成后未发现本次 Gradle wrapper、Gradle daemon 或 JMH 进程残留；无需 kill 其他进程。
- 没有逐字节 round-trip 正确性检查，仅消费返回长度；本次不是协议正确性测试。
- JMH CI 描述当前实验的迭代均值不确定性，不涵盖跨机器、真实数据分布和后台负载偏差；2 fork 仍有限。JSON 的 scorePercentiles 也是迭代均值分位数，不是业务请求尾延迟。
- 没测压缩率、线路字节数、RAW 帧、批等待、调度、池化分配、实际 handler、网络、多人服务器 TPS/CPU/RTT。不能从平均微秒数推导端到端带宽节省或 <1 ms 服务保证。

## 6. 与旧文档的有限对照

旧文档 /project/Velozip/docs/BENCHMARK.md 使用 JDK 25.0.3、1 fork、2–3 次预热/测量，未给出相同硬件负载、JVM 限制、原始 JSON 和误差范围；本次为 JDK 25.0.4.1、2 fork、5/8 次迭代。因此只列同名输入参数的参考值，**不计算版本回归/提升百分比，不作统计显著性比较**。

| 同名参数 | 旧文档 µs/op（无 CI） | 本次 µs/op（99.9% 误差半宽） |
|---|---:|---:|
| compressOnly MC_LIKE 32 KiB | 22.02 | 27.608 ± 2.683 |
| compressOnly MC_LIKE 64 KiB | 50.81 | 80.098 ± 23.777 |
| ctxRoundTrip MC_LIKE 32 KiB | 42.11 | 45.531 ± 1.803 |
| ctxRoundTrip MC_LIKE 64 KiB | 88.87 | 99.227 ± 3.903 |

旧文档 batch 数据仅约 10⁻⁵ s/op，且“full round trip”“pooled”“allocation-dominated”口径有误，不能作为严格基线。旧文档的真实服务器带宽节省 79–83% 属于另外的历史 E2E 实验，**本次未重测、未验证，也不是 v0.3.0 新结果**。若要评估版本差异，应在同一机器与参数下交替运行固定旧 commit 与本 commit，并另做真实流量的端到端测试。

## 7. 精确复现与原始文件

执行脚本：/project/Velozip/build/benchmark-0.3.0-20260907/run.sh。脚本导出的 JAVA_HOME 和 PATH 只作用于本次子进程，没有修改环境配置文件。使用缓存中的官方 wrapper 9.7.1，未切换 /env/gradle 链接。

核心完整命令（重新执行前建议更换结果目录，避免覆盖本次原始证据）：

```bash
export JAVA_HOME=/env/zulu25
export PATH=/env/zulu25/bin:/env/node/bin:$PATH
bash /project/Velozip/gradlew -p /project/Velozip --no-daemon --max-workers=1 --no-parallel -Dorg.gradle.java.installations.paths=/env/zulu25 -Dorg.gradle.java.installations.auto-detect=false -Dorg.gradle.java.installations.auto-download=false '-Dorg.gradle.jvmargs=-Xmx384m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' :velozip-benchmark:jar :velozip-benchmark:jmh --rerun-tasks --args='CompressionBenchmark -wi 5 -w 1s -i 8 -r 1s -f 2 -t 1 -bm avgt -tu us -foe true -jvm /env/zulu25/bin/java -jvmArgs "-Xms128m -Xmx384m -XX:MaxDirectMemorySize=128m -XX:ActiveProcessorCount=2 -XX:+UseG1GC -Dio.netty.leakDetection.level=simple" -rf json -rff /project/Velozip/build/benchmark-0.3.0-20260907/results.json' > "$OUT/jmh.log" 2>&1
```

- JMH 原始 JSON（含每 fork 数据、误差与 CI）：/project/Velozip/build/benchmark-0.3.0-20260907/results.json
- 完整编译/JMH 日志：/project/Velozip/build/benchmark-0.3.0-20260907/jmh.log
- 环境快照、CPU、JDK、Gradle、初始进程清单：/project/Velozip/build/benchmark-0.3.0-20260907/environment.txt
- 结束时间：/project/Velozip/build/benchmark-0.3.0-20260907/finished.txt
- JAR 与 gradlew.bat 校验和：/project/Velozip/build/benchmark-0.3.0-20260907/artifacts.sha256
- 38 组完整性、样本数、波动与原始结果校验：/project/Velozip/build/benchmark-0.3.0-20260907/validation.json
- 本报告：/project/Velozip/docs/BENCHMARK-0.3.0.zh-CN.md

### GitHub 长期归档

为避免本地 `build` 清理后丢失测量证据，以下文件随本报告纳入版本控制：

- [原始 JMH JSON](benchmarks/v0.3.0-20260907/results.json)：含全部 38 组结果、每 fork 样本、运行参数及置信区间。
- [完整性核验](benchmarks/v0.3.0-20260907/validation.json)：含样本数量、波动分析及原始 JSON 的 SHA-256。

上文绝对路径为测量时的本地证据路径。完整运行日志、环境进程快照和临时脚本仍留在本地 `build`，未上传；本报告已包含环境摘要与复现命令。
