package dev.tcpprobe.web;

import dev.tcpprobe.ProbeProperties;
import dev.tcpprobe.probe.ProbeResult;
import dev.tcpprobe.probe.ProbeTarget;
import dev.tcpprobe.service.ProbeService;
import dev.tcpprobe.service.ProbeTask;
import dev.tcpprobe.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP 接口层：只有四个端点，逻辑尽量薄，重活都在 {@link ProbeService} 与探测器里。
 */
@RestController
public class ProbeController {

    /** 长轮询最多等 10 秒，超过这个时长就属于调用方的问题。 */
    static final int MAX_LONG_POLL_MS = 10_000;

    private static final int MIN_TIMEOUT_MS = 50;
    private static final int MAX_TIMEOUT_MS = 60_000;

    private final ProbeService service;
    private final ProbeProperties properties;
    private final String version;

    public ProbeController(ProbeService service, ProbeProperties properties,
                           @Value("${app.version:1.0.0}") String version) {
        this.service = service;
        this.properties = properties;
        this.version = version;
    }

    /** 自描述索引：新部署时 curl 一下就够。 */
    @GetMapping("/")
    public Map<String, Object> root() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "tcp-probe-service");
        body.put("version", version);
        body.put("endpoints", List.of(
                "POST /api/v1/probes",
                "GET  /api/v1/probes/{taskId}?waitMs=0",
                "GET  /api/v1/stats",
                "GET  /healthz"));
        return body;
    }

    /** 只检查进程存活，不做任何探测，因此在负载下依然很廉价。 */
    @GetMapping("/healthz")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("service", "tcp-probe-service");
        body.put("version", version);
        body.putAll(service.stats().get("tasks") instanceof Map<?, ?> tasks ? Map.of() : Map.of());
        Map<String, Object> stats = service.stats();
        body.put("tasksRunning", stats.get("tasks") instanceof Map<?, ?> tasks ? tasks.get("running") : 0);
        body.put("probesInFlight", stats.get("probes") instanceof Map<?, ?> probes ? probes.get("inFlight") : 0);
        return body;
    }

    /** 运维用的计数器。 */
    @GetMapping("/api/v1/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    /**
     * 提交一批目标。
     *
     * <p>{@code mode=sync} 时在同一个请求上等结果（最多 {@code probe.sync-max-wait-ms}）；
     * {@code async} 时立刻返回 202 和任务 id。两种模式走同一条执行路径，区别只在等不等。</p>
     */
    @PostMapping("/api/v1/probes")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestBody(required = false) ProbeRequest body,
            @RequestParam(name = "mode", required = false) String queryMode,
            HttpServletRequest request) {

        // 先看长度再解析：超大的请求体没有解析价值，也不该消耗限流额度。
        // 只覆盖带 Content-Length 的请求；分块传输的请求体由目标数上限兜底。
        if (request.getContentLengthLong() > properties.maxBodyBytes()) {
            throw ApiException.payloadTooLarge(properties.maxBodyBytes());
        }

        RateLimiter.Decision decision = service.rateLimiter().acquire(request.getRemoteAddr());
        if (!decision.allowed()) {
            throw ApiException.tooManyRequests(
                    decision.limit() + " rate limit exceeded; retry in " + decision.retryAfterSeconds() + "s",
                    decision.retryAfterSeconds());
        }

        ProbeRequest payload = body == null ? new ProbeRequest(null, null, null) : body;
        List<ProbeTarget> targets = parseTargets(payload.targets());

        int timeoutMs = payload.timeoutMs() == null ? properties.timeoutMs() : payload.timeoutMs();
        if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
            throw ApiException.badRequest("INVALID_TIMEOUT",
                    "timeoutMs must be between " + MIN_TIMEOUT_MS + " and " + MAX_TIMEOUT_MS);
        }

        ProbeTask.Mode mode = parseMode(payload.mode() != null ? payload.mode() : queryMode);
        ProbeTask task = service.submit(targets, timeoutMs, mode);
        if (mode == ProbeTask.Mode.SYNC) {
            task.await(properties.syncMaxWaitMs());
        }

        return ResponseEntity.status(task.isFinished() ? 200 : 202)
                .header(HttpHeaders.LOCATION, "/api/v1/probes/" + task.id())
                .header("X-RateLimit-Remaining", String.valueOf(decision.remaining()))
                .body(view(task));
    }

    /**
     * 查询任务。{@code ?waitMs=N} 做长轮询：完成返回 200，未完成返回 202 加当前状态，
     * 客户端因此不需要写密集轮询循环。
     */
    @GetMapping("/api/v1/probes/{taskId}")
    public ResponseEntity<Map<String, Object>> task(
            @PathVariable String taskId,
            @RequestParam(name = "waitMs", defaultValue = "0") int waitMs) {

        // 先校验参数再查任务：格式错误永远是 400，与任务是否存在无关。
        if (waitMs < 0 || waitMs > MAX_LONG_POLL_MS) {
            throw ApiException.badRequest("INVALID_QUERY",
                    "waitMs must be between 0 and " + MAX_LONG_POLL_MS);
        }
        ProbeTask task = service.find(taskId)
                .orElseThrow(() -> ApiException.notFound("task not found: " + taskId));
        if (!task.isFinished() && waitMs > 0) {
            task.await(waitMs);
        }
        return ResponseEntity.status(task.isFinished() ? 200 : 202).body(view(task));
    }

    /** 把目标列表转成 {@link ProbeTarget}，所有错误一次性报回，避免客户端逐个试错。 */
    private List<ProbeTarget> parseTargets(List<Object> raw) {
        if (raw == null) {
            throw ApiException.badRequest("MISSING_TARGETS", "targets is required");
        }
        List<ProbeTarget> targets = new ArrayList<>(raw.size());
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            try {
                targets.add(parseTarget(raw.get(i)));
            } catch (IllegalArgumentException e) {
                if (errors.size() < 10) {
                    errors.add("targets[" + i + "]: " + e.getMessage());
                }
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("INVALID_TARGETS", "one or more targets are invalid", errors);
        }
        return List.copyOf(targets);
    }

    /** 支持 {@code "host:port"} 与 {@code {"host": "...", "port": 22}} 两种元素形式。 */
    private static ProbeTarget parseTarget(Object value) {
        if (value instanceof String text) {
            return ProbeTarget.parse(text);
        }
        if (value instanceof Map<?, ?> map) {
            Object host = map.get("host");
            Object port = map.get("port");
            if (!(host instanceof String hostText) || hostText.isBlank()) {
                throw new IllegalArgumentException("target object needs a non-empty string 'host'");
            }
            if (!(port instanceof Number portNumber)) {
                throw new IllegalArgumentException("target object needs a numeric 'port'");
            }
            return new ProbeTarget(hostText, portNumber.intValue());
        }
        throw new IllegalArgumentException("target must be \"host:port\" or {host, port}");
    }

    private static ProbeTask.Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProbeTask.Mode.ASYNC;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return ProbeTask.Mode.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_MODE", "mode must be 'sync' or 'async'");
        }
    }

    /** 线上格式：领域对象与 JSON 外壳分开，未完成的任务不输出 results。 */
    private Map<String, Object> view(ProbeTask task) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", task.id());
        body.put("status", task.state().name());
        body.put("mode", task.mode().name().toLowerCase(Locale.ROOT));
        body.put("createdAt", task.createdAt());
        body.put("startedAt", task.startedAt());
        body.put("finishedAt", task.finishedAt());
        body.put("timeoutMs", task.timeoutMs());
        body.put("targetCount", task.targets().size());
        Optional.ofNullable(task.error()).ifPresent(error -> body.put("error", error));
        if (task.isFinished()) {
            body.put("summary", task.summary());
            body.put("results", task.results().stream().map(this::resultView).toList());
        }
        return body;
    }

    private Map<String, Object> resultView(ProbeResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("target", result.target().display());
        body.put("host", result.target().host());
        body.put("port", result.target().port());
        body.put("status", result.status().name());
        body.put("latencyMs", result.latencyMs());
        body.put("dnsMs", result.dnsMs());
        Optional.ofNullable(result.detail()).ifPresent(detail -> body.put("detail", detail));
        return body;
    }
}
