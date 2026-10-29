package dev.tcpprobe.config;

/**
 * Immutable runtime configuration.
 *
 * <p>All values are validated by {@link ConfigLoader}; a bad configuration fails fast at startup
 * instead of surfacing as a confusing runtime error under load.</p>
 */
public record Config(
        String bindAddress,
        int port,
        int probeTimeoutMs,
        int probeDnsTimeoutMs,
        int maxProbesInFlight,
        int maxTargetsPerRequest,
        int maxConcurrentTasks,
        int taskWorkers,
        long taskTtlSeconds,
        double globalRateRps,
        int globalRateBurst,
        double clientRateRps,
        int clientRateBurst,
        int syncMaxWaitMs,
        int maxRequestBodyBytes,
        boolean trustProxyHeader,
        String version) {

    /** Upper bound on the overall wall-clock time one batch may consume before it is cut short. */
    public long overallDeadlineMs(int targetCount) {
        long perTarget = (long) probeDnsTimeoutMs + probeTimeoutMs + 500L;
        long perWave = (long) Math.ceil((double) targetCount / Math.max(1, maxProbesInFlight)) * perTarget;
        return Math.min(perWave + 2_000L, (long) syncMaxWaitMs + 5_000L);
    }

    public Config withPort(int newPort) {
        return new Config(bindAddress, newPort, probeTimeoutMs, probeDnsTimeoutMs, maxProbesInFlight,
                maxTargetsPerRequest, maxConcurrentTasks, taskWorkers, taskTtlSeconds, globalRateRps,
                globalRateBurst, clientRateRps, clientRateBurst, syncMaxWaitMs, maxRequestBodyBytes,
                trustProxyHeader, version);
    }

    public static Config defaults() {
        return new Config(
                "0.0.0.0",
                8080,
                2000,
                2000,
                64,
                200,
                16,
                4,
                600,
                50.0,
                100,
                10.0,
                20,
                30000,
                1_048_576,
                false,
                "1.0.0");
    }
}
