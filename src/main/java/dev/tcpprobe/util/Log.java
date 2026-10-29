package dev.tcpprobe.util;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Tiny single-line logger.
 *
 * <p>The service deliberately avoids a logging framework so that the delivered jar has no
 * external runtime dependency; the trade-off is that only plain text output is available.
 * Every line is emitted atomically through {@link PrintStream#println(String)} so that
 * interleaving between request threads cannot corrupt a line.</p>
 */
public final class Log {

    public enum Level {
        DEBUG(10), INFO(20), WARN(30), ERROR(40), OFF(100);

        final int weight;

        Level(int weight) {
            this.weight = weight;
        }

        public static Level parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return INFO;
            }
            String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
            for (Level l : values()) {
                if (l.name().equals(v)) {
                    return l;
                }
            }
            throw new IllegalArgumentException("unknown log level: " + raw);
        }
    }

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static volatile Level level = Level.INFO;
    private static volatile PrintStream out = System.out;

    private Log() {
    }

    public static void setLevel(Level newLevel) {
        level = newLevel == null ? Level.INFO : newLevel;
    }

    public static Level level() {
        return level;
    }

    /** Redirects output; used by tests to keep the build log readable. */
    public static void setOut(PrintStream stream) {
        out = stream == null ? System.out : stream;
    }

    public static boolean isDebug() {
        return level.weight <= Level.DEBUG.weight;
    }

    public static void debug(String message, Object... args) {
        log(Level.DEBUG, message, args);
    }

    public static void info(String message, Object... args) {
        log(Level.INFO, message, args);
    }

    public static void warn(String message, Object... args) {
        log(Level.WARN, message, args);
    }

    public static void error(String message, Object... args) {
        log(Level.ERROR, message, args);
    }

    public static void error(String message, Throwable t) {
        log(Level.ERROR, message + " | " + t.getClass().getSimpleName() + ": " + safeMessage(t));
    }

    private static void log(Level l, String message, Object... args) {
        if (l.weight < level.weight) {
            return;
        }
        String text = args == null || args.length == 0 ? message : format(message, args);
        out.println(TS.format(Instant.now()) + " " + pad(l.name()) + " " + text);
    }

    private static String format(String pattern, Object[] args) {
        try {
            return String.format(pattern, args);
        } catch (RuntimeException e) {
            return pattern + " " + java.util.Arrays.toString(args);
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? "(no message)" : m;
    }

    private static String pad(String s) {
        return s.length() >= 5 ? s : s + " ".repeat(5 - s.length());
    }
}
