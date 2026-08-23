package dev.velozip.common.compression;

import com.github.luben.zstd.ZstdDecompressCtx;
import dev.velozip.common.frame.VeloZipFrameException;
import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;

/**
 * Per-channel Zstd decompressor (one per event-loop thread, see
 * {@link ZstdCompressor}). Expects the caller to have already validated the
 * declared sizes against the configured limits.
 */
public final class ZstdDecompressor implements AutoCloseable {

    private final ZstdDecompressCtx ctx = new ZstdDecompressCtx();

    /**
     * Decompresses {@code src} into {@code dst} (both direct, sized by the
     * frame header). zstd-jni requires direct, non-read-only buffers.
     *
     * @return decompressed size, or 0 on failure/short dst
     */
    public int decompress(ByteBuf dst, int decompressedSize, ByteBuf src, int compressedSize) {
        if (!src.isDirect()) {
            throw new VeloZipFrameException("internal: decompress source must be a direct buffer");
        }
        ByteBuffer dstNio = dst.nioBuffer(dst.readerIndex(), decompressedSize);
        ByteBuffer srcNio = src.nioBuffer(src.readerIndex(), compressedSize);
        int written = ctx.decompressDirectByteBuffer(dstNio, 0, decompressedSize, srcNio, 0, compressedSize);
        if (written > 0) {
            dst.writerIndex(dst.writerIndex() + written);
        }
        return written;
    }

    @Override
    public void close() {
        ctx.close();
    }
}
