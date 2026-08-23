package dev.velozip.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import dev.velozip.common.VeloZip;
import dev.velozip.common.metrics.VeloZipMetrics;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Locale;

/** /velozip [status|stats] — output layout follows prompt §23. */
public final class VeloZipCommand implements SimpleCommand {

    private final VeloZipMetrics metrics;
    private final String pluginVersion;
    private final String platformDescription;

    public VeloZipCommand(VeloZipMetrics metrics, String pluginVersion, String platformDescription) {
        this.metrics = metrics;
        this.pluginVersion = pluginVersion;
        this.platformDescription = platformDescription;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> source.sendMessage(status());
            case "stats" -> source.sendMessage(stats());
            default -> source.sendMessage(Component.text(
                    "Usage: /velozip [status|stats]", NamedTextColor.RED));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            return List.of("status", "stats");
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velozip.command");
    }

    private Component status() {
        return Component.text()
                .append(line("VeloZip " + pluginVersion, NamedTextColor.GOLD))
                .append(line("Transport Protocol: " + VeloZip.TRANSPORT_PROTOCOL))
                .append(line("Compression: Zstd Level " + VeloZip.COMPRESSION_LEVEL))
                .append(line("Platform: " + platformDescription))
                .append(line("Active connections: " + metrics.activeConnections.sum()))
                .append(line("Total connections: " + metrics.totalConnections.sum()))
                .build();
    }

    private Component stats() {
        VeloZipMetrics.Snapshot s = metrics.snapshot();
        return Component.text()
                .append(line("VeloZip " + pluginVersion + " — transport statistics", NamedTextColor.GOLD))
                .append(line(""))
                .append(line("Original:    " + human(s.originalBytes())))
                .append(line("Transferred: " + human(s.wireBytes())))
                .append(line("Saved:       " + human(s.savedBytes())))
                .append(line("Bandwidth reduction: "
                        + (Double.isNaN(s.bandwidthReduction()) ? "n/a" : String.format(Locale.ROOT, "%.1f%%", s.bandwidthReduction()))))
                .append(line(""))
                .append(line("Frames: " + s.totalFrames() + " (ZSTD " + s.zstdFrames()
                        + ", RAW " + s.rawFrames() + ")"))
                .append(line("Average batch: " + (Double.isNaN(s.averageBatchBytes())
                        ? "n/a" : String.format(Locale.ROOT, "%.1f KiB", s.averageBatchBytes() / 1024.0))))
                .append(line("Compression ops: " + s.compressionOps()
                        + ", decompression ops: " + s.decompressionOps()))
                .append(line("Compress:    " + latency(s.compressP50Us(), s.compressP95Us(), s.compressP99Us())))
                .append(line("Decompress:  " + latency(s.decompressP50Us(), s.decompressP95Us(), s.decompressP99Us())))
                .build();
    }

    private static String latency(double p50, double p95, double p99) {
        if (Double.isNaN(p50)) {
            return "no data yet";
        }
        return String.format(Locale.ROOT, "P50 %.0f µs, P95 %.0f µs, P99 %.0f µs", p50, p95, p99);
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color);
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        for (String unit : new String[]{"KiB", "MiB", "GiB", "TiB"}) {
            v /= 1024;
            if (v < 1024) {
                return String.format(Locale.ROOT, "%.2f %s (%d B)", v, unit, bytes);
            }
        }
        return bytes + " B";
    }
}
