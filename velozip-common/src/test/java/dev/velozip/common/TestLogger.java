package dev.velozip.common;

import dev.velozip.common.logging.VeloZipLogger;

public final class TestLogger implements VeloZipLogger {

    public static final VeloZipLogger INSTANCE = new TestLogger();

    private TestLogger() {
    }

    @Override
    public void info(String message, Object... args) {
        System.out.println("[INFO] " + format(message, args));
    }

    @Override
    public void warn(String message, Object... args) {
        System.out.println("[WARN] " + format(message, args));
    }

    @Override
    public void error(String message, Throwable throwable, Object... args) {
        System.err.println("[ERROR] " + format(message, args) + " " + throwable);
    }

    @Override
    public void debug(String message, Object... args) {
        // silent by default
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
