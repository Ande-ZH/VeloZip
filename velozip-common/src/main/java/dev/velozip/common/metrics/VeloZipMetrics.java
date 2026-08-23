package dev.velozip.common.metrics;

import org.HdrHistogram.Histogram;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide VeloZip statistics. Hot-path updates are a LongAdder increment
 * plus (for compression calls) one histogram record — both single-digit
 * nanosecond order costs.
 *
 * <p>Latency histograms use a 1 ns .. 100 ms range with 2 significant digits,
 * which keeps each histogram small while still answering P50/P95/P99 for the
 * &lt; 1 ms budget.
 */
public final class VeloZipMetrics {

    private static final long HISTO_MAX = 100_000_000L; // 100 ms in nanos

    public final LongAdder originalBytes = new LongAdder();
    public final LongAdder wireBytes = new LongAdder();
    public final LongAdder rawFrames = new LongAdder();
    public final LongAdder zstdFrames = new LongAdder();
    public final LongAdder compressionOps = new LongAdder();
    public final LongAdder decompressionOps = new LongAdder();
    public final LongAdder batches = new LongAdder();
    public final LongAdder activeConnections = new LongAdder();
    public final LongAdder totalConnections = new LongAdder();

    // Package-private mutable histograms; read only through snapshot().
    final Histogram compressNanos = new Histogram(HISTO_MAX, 2);
    final Histogram decompressNanos = new Histogram(HISTO_MAX, 2);
    private final Object histLock = new Object();

    public void recordCompress(long nanos) {
        if (nanos >= 0 && nanos <= HISTO_MAX) {
            synchronized (histLock) {
                compressNanos.recordValue(nanos);
            }
        }
    }

    public void recordDecompress(long nanos) {
        if (nanos >= 0 && nanos <= HISTO_MAX) {
            synchronized (histLock) {
                decompressNanos.recordValue(nanos);
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (histLock) {
            return new Snapshot(
                    originalBytes.sum(), wireBytes.sum(),
                    rawFrames.sum(), zstdFrames.sum(),
                    compressionOps.sum(), decompressionOps.sum(),
                    batches.sum(), activeConnections.sum(), totalConnections.sum(),
                    percentile(compressNanos, 50), percentile(compressNanos, 95), percentile(compressNanos, 99),
                    percentile(decompressNanos, 50), percentile(decompressNanos, 95), percentile(decompressNanos, 99));
        }
    }

    private static double percentile(Histogram h, double p) {
        return h.getTotalCount() == 0 ? Double.NaN : h.getValueAtPercentile(p) / 1000.0; // -> microseconds
    }

    /** Immutable view for command output. Percentile values are in microseconds. */
    public record Snapshot(
            long originalBytes, long wireBytes,
            long rawFrames, long zstdFrames,
            long compressionOps, long decompressionOps,
            long batches, long activeConnections, long totalConnections,
            double compressP50Us, double compressP95Us, double compressP99Us,
            double decompressP50Us, double decompressP95Us, double decompressP99Us) {

        public long totalFrames() {
            return rawFrames + zstdFrames;
        }

        public long savedBytes() {
            return Math.max(0, originalBytes - wireBytes);
        }

        public double bandwidthReduction() {
            return originalBytes == 0 ? Double.NaN : (double) savedBytes() / originalBytes * 100.0;
        }

        public double averageBatchBytes() {
            return batches == 0 ? Double.NaN : (double) originalBytes / batches;
        }
    }
}
