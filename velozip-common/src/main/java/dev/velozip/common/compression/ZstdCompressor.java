package dev.velozip.common.compression;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import dev.velozip.common.VeloZip;
import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;

/**
 * Per-channel Zstd level-1 compressor. A ZstdCompressCtx is not thread-safe;
 * exactly one instance lives on the channel's event loop thread.
 *
 * <p>{@link #close()} must be called when the channel (or the handler owning
 * this compressor) goes away; zstd-jni close() is idempotent and also has a
 * Cleaner backstop, but explicit closing keeps native memory bounded.
 */
public final class ZstdCompressor implements AutoCloseable {

    private final ZstdCompressCtx ctx = new ZstdCompressCtx();

    public ZstdCompressor() {
        ctx.setLevel(VeloZip.COMPRESSION_LEVEL);
    }

    /**
     * Compresses {@code src} (direct, fully readable) into {@code dst} (direct,
     * with at least {@link #bound(int)} writable bytes).
     *
     * @return compressed size, or 0 if dst was too small
     */
    public int compress(ByteBuf dst, ByteBuf src) {
        ByteBuffer dstNio = dst.nioBuffer(dst.readerIndex(), dst.writableBytes());
        ByteBuffer srcNio = src.nioBuffer(src.readerIndex(), src.readableBytes());
        int written = ctx.compressDirectByteBuffer(dstNio, 0, dst.writableBytes(), srcNio, 0, src.readableBytes());
        if (written > 0) {
            dst.writerIndex(dst.writerIndex() + written);
        }
        return written;
    }

    public int bound(int uncompressedSize) {
        return (int) Zstd.compressBound(uncompressedSize);
    }

    @Override
    public void close() {
        ctx.close();
    }
}
