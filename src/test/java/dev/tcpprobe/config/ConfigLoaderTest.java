package dev.tcpprobe.config;

import dev.tcpprobe.util.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private Path properties;

    @BeforeEach
    void setUp() throws IOException {
        properties = Files.createTempFile("probe-config", ".properties");
    }

    @AfterEach
    void tearDown() throws IOException {
        Log.setLevel(Log.Level.INFO);
        Files.deleteIfExists(properties);
    }

    private Config load(String[] args, Map<String, String> env) {
        return ConfigLoader.load(args, env, properties);
    }

    @Test
    void fallsBackToDefaults() {
        Config config = load(new String[0], Map.of());
        assertEquals(8080, config.port());
        assertEquals(2000, config.probeTimeoutMs());
        assertEquals(16, config.maxConcurrentTasks());
        assertTrue(config.bindAddress().contains("."));
    }

    @Test
    void layerPrecedenceIsFileThenEnvThenCli() throws IOException {
        Files.writeString(properties, """
                server.port=9000
                probe.timeout-ms=1500
                server.bind=0.0.0.0
                """);
        Config config = load(
                new String[]{"--server.port=9100"},
                Map.of("PROBE_PROBE_TIMEOUT_MS", "2500", "PROBE_SERVER_BIND", "127.0.0.1"));
        assertEquals(9100, config.port(), "CLI beats env and file");
        assertEquals(2500, config.probeTimeoutMs(), "env beats file");
        assertEquals("127.0.0.1", config.bindAddress(), "env applies when CLI is silent");
    }

    @Test
    void acceptsEqualsAndBareKeys() {
        Config config = load(new String[]{"--server.port=9200", "--http.trust-proxy-header"}, Map.of());
        assertEquals(9200, config.port());
        assertTrue(config.trustProxyHeader());
    }

    @Test
    void rejectsUnknownKeys() {
        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> load(new String[]{"--probe.timout-ms=100"}, Map.of()));
        assertTrue(e.getMessage().contains("unknown configuration key"), e.getMessage());
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThrows(ConfigurationException.class, () -> load(new String[]{"--server.port=70000"}, Map.of()));
        assertThrows(ConfigurationException.class, () -> load(new String[]{"--probe.timeout-ms=10"}, Map.of()));
        assertThrows(ConfigurationException.class, () -> load(new String[]{"--rate-limit.client.rps=-1"}, Map.of()));
    }

    @Test
    void rejectsMalformedBoolean() {
        assertThrows(ConfigurationException.class,
                () -> load(new String[]{"--http.trust-proxy-header=maybe"}, Map.of()));
    }

    @Test
    void rejectsPositionalArguments() {
        assertThrows(ConfigurationException.class, () -> load(new String[]{"start"}, Map.of()));
    }

    @Test
    void failsWhenExplicitFileIsMissing() {
        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> ConfigLoader.load(new String[0], Map.of(), properties.resolveSibling("does-not-exist.properties")));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
    }

    @Test
    void appliesLogLevel() {
        load(new String[]{"--log.level=DEBUG"}, Map.of());
        assertTrue(Log.isDebug());
    }

    @Test
    void envKeyUsesUpperSnakeCase() {
        assertEquals("PROBE_RATE_LIMIT_GLOBAL_RPS", ConfigLoader.envKey("rate-limit.global.rps"));
        assertEquals("PROBE_SERVER_PORT", ConfigLoader.envKey("server.port"));
    }

    @Test
    void configFlagSelectsAnAlternativeFile() throws IOException {
        Files.writeString(properties, "server.port=9400\n");
        // 两种写法都必须可用，而且这个 flag 本身不能被当成未知 key 拒绝。
        Config fromEquals = ConfigLoader.load(new String[]{"--config=" + properties, "--server.port=9401"});
        assertEquals(9401, fromEquals.port());

        Config fromSeparateArg = ConfigLoader.load(new String[]{"--config", properties.toString()});
        assertEquals(9400, fromSeparateArg.port());
    }

    @Test
    void configFlagRequiresAPath() {
        assertThrows(ConfigurationException.class, () -> ConfigLoader.load(new String[]{"--config"}));
        assertThrows(ConfigurationException.class, () -> ConfigLoader.load(new String[]{"--config="}));
    }

    @Test
    void summaryExposesTheEffectiveBindAddress() {
        Map<String, Object> summary = ConfigLoader.summary(load(new String[]{"--server.port=9300"}, Map.of()));
        assertEquals("0.0.0.0:9300", summary.get("bind"));
    }
}
