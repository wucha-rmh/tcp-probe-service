package dev.tcpprobe.util;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 极简的单行日志器。
 *
 * <p>服务刻意不使用日志框架，以便交付的 jar 不带有任何外部运行时依赖；
 * 代价是只能提供纯文本输出。每一行都通过 {@link PrintStream#println(String)} 原子地输出，
 * 因此请求线程之间的交错不会破坏某一行。</p>
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

    /** 重定向输出；测试用它保持构建日志可读。 */
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
