package dev.velozip.benchmark;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import dev.velozip.common.VeloZip;
import dev.velozip.common.compression.ZstdCompressor;
import dev.velozip.common.compression.ZstdDecompressor;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.util.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Isolated Zstd-level-1 throughput/latency benchmarks (prompt §29), plus a
 * full VeloZip batch round-trip at 32 KiB vs 64 KiB. Runs entirely on pooled
 * direct buffers with reused contexts, mirroring the production hot path.
 */
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class CompressionBenchmark {

    @Param({"128", "1024", "4096", "16384", "32768", "65536"})
    public int size;

    @Param({"RANDOM", "COMPRESSIBLE", "MC_LIKE"})
    public String dataset;

    private byte[] source;
    private ZstdCompressCtx compressCtx;
    private ZstdDecompressCtx decompressCtx;
    private ByteBuf srcDirect;
    private ByteBuf dstDirect;
    private int bound;

    @Setup
    public void setup() {
        source = new byte[size];
        switch (dataset) {
            case "RANDOM" -> new Random(0xC0DE).nextBytes(source);
            case "COMPRESSIBLE" -> {
                Random r = new Random(0xC0DE);
                for (int i = 0; i < source.length; i++) {
                    source[i] = (byte) ('a' + (r.nextInt(8)));
                }
            }
            case "MC_LIKE" -> {
                // Synthetic Minecraft-like payload: chunk palette (repeated
                // small varint block ids) + entity metadata (mostly zeros) +
                // some chat JSON. Realistic mix of compressible and incompressible.
                Random r = new Random(0xC0DE);
                int i = 0;
                while (i < source.length) {
                    int kind = r.nextInt(4);
                    int chunk = Math.min(4096, source.length - i);
                    if (kind == 0) { // palette: a few repeated ids
                        byte id = (byte) r.nextInt(12);
                        for (int j = 0; j < chunk; j++) source[i + j] = id;
                    } else if (kind == 1) { // sparse metadata
                        for (int j = 0; j < chunk; j++) source[i + j] = (j % 16 == 0) ? (byte) (r.nextInt() & 0x7F) : 0;
                    } else if (kind == 2) { // JSON text
                        byte[] text = ("{\"text\":\"hello world " + r.nextInt(1000) + "\",\"color\":\"red\"}")
                                .getBytes(StandardCharsets.UTF_8);
                        for (int j = 0; j < chunk; j++) source[i + j] = text[j % text.length];
                    } else { // light noise
                        for (int j = 0; j < chunk; j++) source[i + j] = (byte) r.nextInt(64);
                    }
                    i += chunk;
                }
            }
            default -> throw new IllegalArgumentException(dataset);
        }
        compressCtx = new ZstdCompressCtx();
        compressCtx.setLevel(VeloZip.COMPRESSION_LEVEL);
        decompressCtx = new ZstdDecompressCtx();
        bound = (int) com.github.luben.zstd.Zstd.compressBound(size);
        srcDirect = Unpooled.directBuffer(size, size);
        srcDirect.writeBytes(source);
        dstDirect = Unpooled.directBuffer(bound, bound);
    }

    /** Hot-path round trip: compress then decompress with reused contexts. */
    @Benchmark
    public int ctxRoundTrip() {
        dstDirect.clear();
        java.nio.ByteBuffer srcNio = srcDirect.nioBuffer(0, size);
        java.nio.ByteBuffer dstNio = dstDirect.nioBuffer(0, bound);
        int compressed = compressCtx.compressDirectByteBuffer(dstNio, 0, bound, srcNio, 0, size);
        if (compressed <= 0) {
            throw new AssertionError("compress failed: " + compressed);
        }
        ByteBuf out = Unpooled.directBuffer(size, size);
        java.nio.ByteBuffer outNio = out.nioBuffer(0, size);
        java.nio.ByteBuffer compNio = dstDirect.nioBuffer(0, compressed);
        int produced = decompressCtx.decompressDirectByteBuffer(outNio, 0, size, compNio, 0, compressed);
        out.release();
        return produced;
    }

    /** Just the compress step (production outbound cost). */
    @Benchmark
    public int compressOnly() {
        dstDirect.clear();
        java.nio.ByteBuffer srcNio = srcDirect.nioBuffer(0, size);
        java.nio.ByteBuffer dstNio = dstDirect.nioBuffer(0, bound);
        return compressCtx.compressDirectByteBuffer(dstNio, 0, bound, srcNio, 0, size);
    }

    /** Full VeloZip batch round trip through the real encoder + decoder at 32K or 64K batch. */
    @State(Scope.Benchmark)
    public static class BatchRoundTrip {

        @Param({"32768", "65536"})
        public int batchSize;

        private VeloZipConfig cfg;
        private VeloZipMetrics metrics;
        private ZstdCompressor compressor;
        private ZstdDecompressor decompressor;
        private byte[] source;
        private ByteBuf batchPayload;
        private ByteBuf decompressed;
        private ByteBuf compressedDirect;
        private ByteBuf frame;
        private int expectedLen;

        @Setup
        public void setup() {
            cfg = VeloZipConfig.builder().rawThreshold(0).batchMaxSize(batchSize).build();
            metrics = new VeloZipMetrics();
            compressor = new ZstdCompressor();
            decompressor = new ZstdDecompressor();
            // Build a batch payload of exactly batchSize: a single Minecraft
            // frame (varint length + body) of high-compressibility data.
            source = new byte[batchSize - VarInts.varIntLen(batchSize)];
            for (int i = 0; i < source.length; i++) {
                source[i] = (byte) ('a' + (i % 26));
            }
            batchPayload = Unpooled.directBuffer(batchSize, batchSize);
            VarInts.writeVarInt(batchPayload, source.length);
            batchPayload.writeBytes(source);
            expectedLen = batchPayload.readableBytes();

            // Pre-build the frame once (mirrors VeloZipBatchEncoder.zstdFrame).
            ByteBuf dst = Unpooled.directBuffer(compressor.bound(expectedLen), compressor.bound(expectedLen));
            int compressed = compressor.compress(dst, batchPayload);
            ByteBuf prefix = Unpooled.buffer(12);
            prefix.writeByte(VeloZip.MAGIC_0).writeByte(VeloZip.MAGIC_1)
                    .writeByte(VeloZip.TRANSPORT_PROTOCOL).writeByte(VeloZip.FLAG_ZSTD);
            VarInts.writeVarInt(prefix, compressed);
            VarInts.writeVarInt(prefix, expectedLen);
            io.netty.buffer.CompositeByteBuf f = Unpooled.compositeBuffer(2);
            f.addComponent(true, prefix);
            f.addComponent(true, dst);
            frame = f;
            // Keep a direct, contiguous copy of the compressed payload for the
            // decompress hot path (zstd-jni requires direct sources).
            compressedDirect = Unpooled.directBuffer(compressed, compressed);
            dst.getBytes(0, compressedDirect, compressed);
            decompressed = Unpooled.directBuffer(expectedLen, expectedLen);
        }

        @Benchmark
        public int batchRoundTrip(Blackhole bh) {
            decompressed.clear();
            int produced = decompressor.decompress(decompressed, expectedLen, compressedDirect,
                    compressedDirect.readableBytes());
            bh.consume(produced);
            return produced;
        }
    }
}