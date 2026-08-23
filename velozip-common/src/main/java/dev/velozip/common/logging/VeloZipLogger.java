package dev.velozip.common.logging;

/**
 * Minimal logging facade injected by each platform (Velocity uses its slf4j
 * logger, the backend uses the Bukkit java.util.logging logger). Keeps
 * velozip-common free of any logging framework.
 */
public interface VeloZipLogger {

    void info(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Throwable throwable, Object... args);

    void debug(String message, Object... args);
}
