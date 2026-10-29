package dev.tcpprobe;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * {@code probe.*} 配置。所有值都有默认值，可以直接用环境变量覆盖，
 * 例如 {@code PROBE_TIMEOUT_MS=1500}、{@code PROBE_RATE_LIMIT_CLIENT_RPS=5}。
 *
 * @param timeoutMs            单次 TCP 握手的预算，毫秒
 * @param dnsTimeoutMs         单次域名解析的预算，毫秒
 * @param maxInFlight          同时进行的探测数；它同时决定探测线程池大小（背压来源）
 * @param maxTargetsPerRequest 单个请求最多提交多少个目标
 * @param maxConcurrentTasks   同时在跑的任务数，超出返回 503
 * @param workers              任务线程数，一个任务占一个线程直到整批结束
 * @param taskTtlSeconds       已完成任务在内存中保留多久
 * @param syncMaxWaitMs        sync 模式在 HTTP 请求上最多等待多久
 * @param maxBodyBytes         请求体上限，超过直接 413
 * @param rateLimit            两级令牌桶限流参数
 */
@ConfigurationProperties(prefix = "probe")
public record ProbeProperties(
        @DefaultValue("2000") int timeoutMs,
        @DefaultValue("2000") int dnsTimeoutMs,
        @DefaultValue("64") int maxInFlight,
        @DefaultValue("200") int maxTargetsPerRequest,
        @DefaultValue("16") int maxConcurrentTasks,
        @DefaultValue("4") int workers,
        @DefaultValue("600") long taskTtlSeconds,
        @DefaultValue("30000") int syncMaxWaitMs,
        @DefaultValue("1048576") long maxBodyBytes,
        @DefaultValue RateLimit rateLimit) {

    /**
     * @param globalRps   整个服务允许的长期速率（每秒请求数）
     * @param globalBurst 全局突发额度
     * @param clientRps   单个客户端 IP 允许的长期速率
     * @param clientBurst 单客户端突发额度
     */
    public record RateLimit(
            @DefaultValue("50") double globalRps,
            @DefaultValue("100") int globalBurst,
            @DefaultValue("10") double clientRps,
            @DefaultValue("20") int clientBurst) {
    }

    /**
     * 一批目标的整体截止时间。
     *
     * <p>按“波次”估算：每波最多 {@code maxInFlight} 个探测，每个探测最坏耗时是 DNS 预算加连接预算。
     * 上限取 {@code syncMaxWaitMs + 5s}，避免一个超大请求把任务线程一直占着。</p>
     */
    public int batchDeadlineMs(int targetCount) {
        int perProbe = dnsTimeoutMs + timeoutMs + 250;
        int waves = (int) Math.max(1, Math.ceil(targetCount / (double) maxInFlight));
        long budget = (long) waves * perProbe + 1000L;
        return (int) Math.min(budget, syncMaxWaitMs + 5000L);
    }

    /** 目标列表的校验顺序与错误文案都在这里，控制器只负责取字段。 */
    public List<String> describe() {
        return List.of(
                "timeoutMs=" + timeoutMs,
                "dnsTimeoutMs=" + dnsTimeoutMs,
                "maxInFlight=" + maxInFlight,
                "maxTargetsPerRequest=" + maxTargetsPerRequest,
                "maxConcurrentTasks=" + maxConcurrentTasks,
                "workers=" + workers,
                "taskTtlSeconds=" + taskTtlSeconds,
                "syncMaxWaitMs=" + syncMaxWaitMs,
                "maxBodyBytes=" + maxBodyBytes,
                "rateLimit=global " + rateLimit.globalRps() + "rps/" + rateLimit.globalBurst()
                        + " burst, client " + rateLimit.clientRps() + "rps/" + rateLimit.clientBurst() + " burst");
    }
}
