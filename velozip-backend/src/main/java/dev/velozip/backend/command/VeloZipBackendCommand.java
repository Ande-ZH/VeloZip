package dev.velozip.backend.command;

import dev.velozip.common.VeloZip;
import dev.velozip.common.metrics.VeloZipMetrics;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

/** /velozip [status|stats] — backend side. */
public final class VeloZipBackendCommand implements CommandExecutor, TabCompleter {

    private final VeloZipMetrics metrics;
    private final String platformDescription;

    public VeloZipBackendCommand(VeloZipMetrics metrics, String platformDescription) {
        this.metrics = metrics;
        this.platformDescription = platformDescription;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                sender.sendMessage("§6VeloZip 0.1.0");
                sender.sendMessage("§7Transport Protocol: " + VeloZip.TRANSPORT_PROTOCOL);
                sender.sendMessage("§7Compression: " + VeloZip.ALGORITHM_ZSTD
                        + " Level " + VeloZip.COMPRESSION_LEVEL);
                sender.sendMessage("§7Platform: " + platformDescription);
                sender.sendMessage("§7Active connections: " + metrics.activeConnections.sum());
                sender.sendMessage("§7Total connections: " + metrics.totalConnections.sum());
            }
            case "stats" -> {
                VeloZipMetrics.Snapshot s = metrics.snapshot();
                sender.sendMessage("§6VeloZip 0.1.0 — transport statistics");
                sender.sendMessage("§7Original:    " + human(s.originalBytes()));
                sender.sendMessage("§7Transferred: " + human(s.wireBytes()));
                sender.sendMessage("§7Saved:       " + human(s.savedBytes()));
                sender.sendMessage("§7Bandwidth reduction: "
                        + (Double.isNaN(s.bandwidthReduction()) ? "n/a"
                        : String.format(Locale.ROOT, "%.1f%%", s.bandwidthReduction())));
                sender.sendMessage("§7Frames: " + s.totalFrames() + " (ZSTD " + s.zstdFrames()
                        + ", RAW " + s.rawFrames() + ")");
                sender.sendMessage("§7Average batch: " + (Double.isNaN(s.averageBatchBytes()) ? "n/a"
                        : String.format(Locale.ROOT, "%.1f KiB", s.averageBatchBytes() / 1024.0)));
                sender.sendMessage("§7Compression ops: " + s.compressionOps()
                        + ", decompression ops: " + s.decompressionOps());
                sender.sendMessage("§7Compress:    " + latency(s.compressP50Us(), s.compressP95Us(), s.compressP99Us()));
                sender.sendMessage("§7Decompress:  " + latency(s.decompressP50Us(), s.decompressP95Us(), s.decompressP99Us()));
            }
            default -> sender.sendMessage("§cUsage: /velozip [status|stats]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length <= 1) {
            return List.of("status", "stats");
        }
        return List.of();
    }

    private static String latency(double p50, double p95, double p99) {
        if (Double.isNaN(p50)) {
            return "no data yet";
        }
        return String.format(Locale.ROOT, "P50 %.0f µs, P95 %.0f µs, P99 %.0f µs", p50, p95, p99);
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
