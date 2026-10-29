package dev.tcpprobe.config;

import dev.tcpprobe.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 按优先级递增的顺序，从四个层次解析出实际生效的配置：
 *
 * <ol>
 *   <li>内置默认值（{@link Config#defaults()}）；</li>
 *   <li>一个 {@code .properties} 文件（默认是 {@code config/application.properties}，
 *       或由 {@code PROBE_CONFIG} 环境变量给出的路径）；</li>
 *   <li>名为 {@code PROBE_} + 大写蛇形 key 的环境变量
 *       （{@code server.port} 对应 {@code PROBE_SERVER_PORT}）；</li>
 *   <li>形如 {@code --key=value} 的命令行参数。</li>
 * </ol>
 *
 * <p>未知 key 会被刻意拒绝：部署清单里的一个拼写错误应当明确失败，
 * 而不是静默回退到默认值。</p>
 */
public final class ConfigLoader {

    static final List<String> KNOWN_KEYS = List.of(
            "server.bind",
            "server.port",
            "probe.timeout-ms",
            "probe.dns-timeout-ms",
            "probe.max-in-flight",
            "probe.max-targets-per-request",
            "task.max-concurrent",
            "task.workers",
            "task.ttl-seconds",
            "rate-limit.global.rps",
            "rate-limit.global.burst",
            "rate-limit.client.rps",
            "rate-limit.client.burst",
            "sync.max-wait-ms",
            "http.max-body-bytes",
            "http.trust-proxy-header",
            "log.level");

    private final Map<String, String> values;

    private ConfigLoader(Map<String, String> values) {
        this.values = values;
    }

    /**
     * 从真实进程环境加上给定的 CLI 参数加载配置。
     *
     * <p>接受 {@code --config=<path>}（或 {@code --config <path>}），用于指向
     * {@code config/application.properties} / {@code $PROBE_CONFIG} 之外的 properties 文件。</p>
     */
    public static Config load(String[] cliArgs) {
        List<String> rest = new ArrayList<>();
        Path explicit = null;
        String[] args = cliArgs == null ? new String[0] : cliArgs;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--config")) {
                if (i + 1 >= args.length) {
                    throw new ConfigurationException("--config requires a file path");
                }
                explicit = Path.of(args[++i].trim());
            } else if (arg.startsWith("--config=")) {
                String value = arg.substring("--config=".length()).trim();
                if (value.isEmpty()) {
                    throw new ConfigurationException("--config requires a file path");
                }
                explicit = Path.of(value);
            } else {
                rest.add(arg);
            }
        }
        return load(rest.toArray(String[]::new), System.getenv(), explicit);
    }

    /**
     * @param cliArgs      传给 {@code main} 的参数，例如 {@code --server.port=9090}
     * @param env          环境变量
     * @param explicitFile 要使用的 properties 文件；{@code null} 表示保持默认查找逻辑
     */
    public static Config load(String[] cliArgs, Map<String, String> env, Path explicitFile) {
        Map<String, String> cli = parseCli(cliArgs);
        Path file = explicitFile;
        if (file == null) {
            String fromEnv = env.get("PROBE_CONFIG");
            file = fromEnv != null && !fromEnv.isBlank()
                    ? Path.of(fromEnv.trim())
                    : Path.of("config", "application.properties");
        }

        Map<String, String> merged = new LinkedHashMap<>();
        if (Files.exists(file)) {
            merged.putAll(readProperties(file));
        } else if (explicitFile != null) {
            throw new ConfigurationException("config file not found: " + explicitFile.toAbsolutePath());
        }

        for (String key : KNOWN_KEYS) {
            String fromEnv = env.get(envKey(key));
            if (fromEnv != null && !fromEnv.isBlank()) {
                merged.put(key, fromEnv.trim());
            }
        }
        merged.putAll(cli);

        ConfigLoader loader = new ConfigLoader(merged);
        loader.validateKeys();
        Log.setLevel(parseLevel(loader.string("log.level", "INFO")));
        return loader.toConfig();
    }

    static Log.Level parseLevel(String raw) {
        try {
            return Log.Level.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(e.getMessage() + " (expected DEBUG, INFO, WARN, ERROR or OFF)");
        }
    }

    static Map<String, String> parseCli(String[] args) {
        Map<String, String> cli = new LinkedHashMap<>();
        if (args == null) {
            return cli;
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null || a.isBlank()) {
                continue;
            }
            if (!a.startsWith("--")) {
                throw new ConfigurationException("unexpected argument \"" + a + "\"; expected --key=value");
            }
            String body = a.substring(2);
            int eq = body.indexOf('=');
            String key;
            String value;
            if (eq >= 0) {
                key = body.substring(0, eq).trim();
                value = body.substring(eq + 1).trim();
            } else {
                key = body.trim();
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i].trim();
                } else {
                    value = "true";
                }
            }
            if (key.isEmpty()) {
                throw new ConfigurationException("empty key in argument \"" + a + "\"");
            }
            cli.put(key, value);
        }
        return cli;
    }

    private static Map<String, String> readProperties(Path file) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new ConfigurationException("cannot read config file " + file.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            out.put(name.trim(), props.getProperty(name).trim());
        }
        return out;
    }

    static String envKey(String key) {
        return "PROBE_" + key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 类型化访问

    private Config toConfig() {
        Config d = Config.defaults();
        return new Config(
                string("server.bind", d.bindAddress()),
                integer("server.port", d.port(), 1, 65535),
                integer("probe.timeout-ms", d.probeTimeoutMs(), 50, 60_000),
                integer("probe.dns-timeout-ms", d.probeDnsTimeoutMs(), 50, 30_000),
                integer("probe.max-in-flight", d.maxProbesInFlight(), 1, 4096),
                integer("probe.max-targets-per-request", d.maxTargetsPerRequest(), 1, 5000),
                integer("task.max-concurrent", d.maxConcurrentTasks(), 1, 1024),
                integer("task.workers", d.taskWorkers(), 1, 256),
                integer("task.ttl-seconds", (int) d.taskTtlSeconds(), 10, 86_400),
                decimal("rate-limit.global.rps", d.globalRateRps(), 0.001, 1_000_000),
                integer("rate-limit.global.burst", d.globalRateBurst(), 1, 1_000_000),
                decimal("rate-limit.client.rps", d.clientRateRps(), 0.001, 1_000_000),
                integer("rate-limit.client.burst", d.clientRateBurst(), 1, 1_000_000),
                integer("sync.max-wait-ms", d.syncMaxWaitMs(), 100, 300_000),
                integer("http.max-body-bytes", d.maxRequestBodyBytes(), 1024, 64 * 1024 * 1024),
                bool("http.trust-proxy-header", d.trustProxyHeader()),
                d.version());
    }

    String string(String key, String fallback) {
        String v = values.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    int integer(String key, int fallback, int min, int max) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + " must be an integer but was \"" + raw + "\"");
        }
        if (v < min || v > max) {
            throw new ConfigurationException(key + " must be within [" + min + ", " + max + "] but was " + v);
        }
        return v;
    }

    double decimal(String key, double fallback, double min, double max) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        double v;
        try {
            v = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + " must be a number but was \"" + raw + "\"");
        }
        if (!Double.isFinite(v) || v < min || v > max) {
            throw new ConfigurationException(key + " must be within [" + min + ", " + max + "] but was " + v);
        }
        return v;
    }

    boolean bool(String key, boolean fallback) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> throw new ConfigurationException(key + " must be a boolean but was \"" + raw + "\"");
        };
    }

    /** 拒绝任何层次都不认识的 key，让错误配置保持可见。 */
    void validateKeys() {
        for (String key : values.keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                throw new ConfigurationException("unknown configuration key \"" + key
                        + "\"; known keys: " + String.join(", ", KNOWN_KEYS));
            }
        }
    }

    /** 启动时写入日志、便于人阅读的摘要。 */
    public static Map<String, Object> summary(Config c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bind", c.bindAddress() + ":" + c.port());
        m.put("probeTimeoutMs", c.probeTimeoutMs());
        m.put("probeDnsTimeoutMs", c.probeDnsTimeoutMs());
        m.put("maxProbesInFlight", c.maxProbesInFlight());
        m.put("maxTargetsPerRequest", c.maxTargetsPerRequest());
        m.put("maxConcurrentTasks", c.maxConcurrentTasks());
        m.put("taskTtlSeconds", c.taskTtlSeconds());
        m.put("globalRate", c.globalRateRps() + "/s burst " + c.globalRateBurst());
        m.put("clientRate", c.clientRateRps() + "/s burst " + c.clientRateBurst());
        m.put("syncMaxWaitMs", c.syncMaxWaitMs());
        return m;
    }
}
