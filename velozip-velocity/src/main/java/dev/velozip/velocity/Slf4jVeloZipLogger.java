package dev.velozip.velocity;

import dev.velozip.common.logging.VeloZipLogger;
import org.slf4j.Logger;

/** Bridges the common logging facade onto the plugin's slf4j logger. */
final class Slf4jVeloZipLogger implements VeloZipLogger {

    private final Logger logger;

    Slf4jVeloZipLogger(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message, Object... args) {
        logger.info(message, args);
    }

    @Override
    public void warn(String message, Object... args) {
        logger.warn(message, args);
    }

    @Override
    public void error(String message, Throwable throwable, Object... args) {
        if (throwable == null) {
            logger.error(message, args);
        } else {
            logger.error(message, throwable, args);
        }
    }

    @Override
    public void debug(String message, Object... args) {
        logger.debug(message, args);
    }
}
