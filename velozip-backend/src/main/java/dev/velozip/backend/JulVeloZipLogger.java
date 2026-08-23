package dev.velozip.backend;

import dev.velozip.common.logging.VeloZipLogger;

import java.util.logging.Logger;

/** Bridges the common logging facade onto the Bukkit JUL logger. */
final class JulVeloZipLogger implements VeloZipLogger {

    private final Logger logger;

    JulVeloZipLogger(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message, Object... args) {
        logger.info(format(message, args));
    }

    @Override
    public void warn(String message, Object... args) {
        logger.warning(format(message, args));
    }

    @Override
    public void error(String message, Throwable throwable, Object... args) {
        String formatted = format(message, args);
        if (throwable == null) {
            logger.severe(formatted);
        } else {
            logger.log(java.util.logging.Level.SEVERE, formatted, throwable);
        }
    }

    @Override
    public void debug(String message, Object... args) {
        logger.fine(format(message, args));
    }

    private static String format(String message, Object[] args) {
        String out = message;
        for (Object arg : args) {
            int idx = out.indexOf("{}");
            if (idx < 0) {
                break;
            }
            out = out.substring(0, idx) + arg + out.substring(idx + 2);
        }
        return out;
    }
}
