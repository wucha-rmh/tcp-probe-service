package dev.tcpprobe.config;

/**
 * 不可变的运行时配置。
 *
 * <p>所有取值都由 {@link ConfigLoader} 校验；非法配置会在启动时快速失败，
 * 而不是在高负载下表现为令人困惑的运行时错误。</p>
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

    /** 单个批次在被提前截断之前允许消耗的总实际耗时上限。 */
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
