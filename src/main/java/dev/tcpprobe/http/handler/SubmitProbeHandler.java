package dev.tcpprobe.http.handler;

import dev.tcpprobe.config.Config;
import dev.tcpprobe.error.ApiException;
import dev.tcpprobe.http.Handler;
import dev.tcpprobe.http.Request;
import dev.tcpprobe.http.Response;
import dev.tcpprobe.http.Views;
import dev.tcpprobe.json.JsonException;
import dev.tcpprobe.json.JsonValue;
import dev.tcpprobe.probe.ProbeTarget;
import dev.tcpprobe.service.ProbeService;
import dev.tcpprobe.service.ProbeTask;
import dev.tcpprobe.service.RateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code POST /api/v1/probes}.
 *
 * <p>请求体：{@code {"targets": ["example.com:443", {"host": "10.0.0.1", "port": 22}],
 * "timeoutMs": 2000, "mode": "async"}}。目标会逐个校验，所有失败会一次性上报，
 * 这样客户端不必为每个错误条目各自往返一次。</p>
 */
public final class SubmitProbeHandler implements Handler {

    private final Config config;
    private final ProbeService service;
    private final RateLimiter rateLimiter;

    public SubmitProbeHandler(Config config, ProbeService service, RateLimiter rateLimiter) {
        this.config = config;
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void handle(Request request, Response response, Map<String, String> pathParams) {
        requireJsonContentType(request);

        String clientKey = request.clientIp(config.trustProxyHeader());
        RateLimiter.Decision decision = rateLimiter.acquire(clientKey);
        if (!decision.allowed()) {
            response.header("X-RateLimit-Remaining", "0");
            throw ApiException.tooManyRequests(
                    decision.limitReason() + " rate limit exceeded; retry in "
                            + decision.retryAfterSeconds() + "s",
                    decision.retryAfterSeconds());
        }
        response.header("X-RateLimit-Remaining", Long.toString(Math.max(0, (long) decision.remaining())));

        JsonValue.Obj body = objectBody(request);
        ProbeTask.Mode mode = parseMode(request, body);
        int timeoutMs = parseTimeout(body);
        List<ProbeTarget> targets = parseTargets(body);

        ProbeTask task = service.submit(targets, timeoutMs, mode);
        if (mode == ProbeTask.Mode.SYNC) {
            awaitCompletion(task);
        }
        response.header("Location", "/api/v1/probes/" + task.id());
        response.json(task.isFinished() ? 200 : 202, Views.task(task));
    }

    private static void requireJsonContentType(Request request) {
        String contentType = request.header("Content-Type");
        if (contentType == null || contentType.isBlank()) {
            return;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("application/json") && !lower.contains("+json")) {
            throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE",
                    "Content-Type must be application/json, got " + contentType);
        }
    }

    private static JsonValue.Obj objectBody(Request request) {
        JsonValue body = request.bodyJson();
        try {
            return body.asObj();
        } catch (JsonException e) {
            throw ApiException.badRequest("INVALID_BODY", "request body must be a JSON object");
        }
    }

    private static ProbeTask.Mode parseMode(Request request, JsonValue.Obj body) {
        String rawMode = null;
        JsonValue modeNode = body.get("mode");
        if (modeNode != null && !modeNode.isNull()) {
            try {
                rawMode = modeNode.asString();
            } catch (JsonException e) {
                throw ApiException.badRequest("INVALID_MODE", "'mode' must be \"sync\" or \"async\"");
            }
        } else {
            rawMode = request.queryParam("mode");
        }
        if (rawMode == null || rawMode.isBlank()) {
            return ProbeTask.Mode.ASYNC;
        }
        return switch (rawMode.trim().toUpperCase(Locale.ROOT)) {
            case "SYNC" -> ProbeTask.Mode.SYNC;
            case "ASYNC" -> ProbeTask.Mode.ASYNC;
            default -> throw ApiException.badRequest("INVALID_MODE",
                    "'mode' must be \"sync\" or \"async\", got \"" + rawMode + "\"");
        };
    }

    private int parseTimeout(JsonValue.Obj body) {
        JsonValue node = body.get("timeoutMs");
        if (node == null || node.isNull()) {
            return config.probeTimeoutMs();
        }
        long value;
        try {
            value = node.asLong();
        } catch (JsonException e) {
            throw ApiException.badRequest("INVALID_TIMEOUT", "'timeoutMs' must be a positive integer");
        }
        if (value < 50 || value > 60_000) {
            throw ApiException.badRequest("INVALID_TIMEOUT", "'timeoutMs' must be within [50, 60000]");
        }
        return (int) value;
    }

    private List<ProbeTarget> parseTargets(JsonValue.Obj body) {
        JsonValue node = body.get("targets");
        if (node == null || node.isNull()) {
            throw ApiException.badRequest("MISSING_TARGETS",
                    "'targets' is required: an array of \"host:port\" strings or {host, port} objects");
        }
        if (!(node instanceof JsonValue.Arr array)) {
            throw ApiException.badRequest("INVALID_TARGETS", "'targets' must be an array");
        }
        if (array.values().isEmpty()) {
            throw ApiException.badRequest("EMPTY_TARGETS", "'targets' must contain at least one entry");
        }
        if (array.size() > config.maxTargetsPerRequest()) {
            throw ApiException.badRequest("TOO_MANY_TARGETS",
                    "at most " + config.maxTargetsPerRequest() + " targets per request, got "
                            + array.size());
        }

        List<ProbeTarget> targets = new ArrayList<>(array.size());
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            try {
                targets.add(parseTarget(array.values().get(i)));
            } catch (IllegalArgumentException | JsonException e) {
                errors.add("targets[" + i + "]: " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("INVALID_TARGETS",
                    errors.size() + " invalid target(s)", errors.subList(0, Math.min(errors.size(), 10)));
        }
        return targets;
    }

    private static ProbeTarget parseTarget(JsonValue item) {
        if (item instanceof JsonValue.Str) {
            return ProbeTarget.parse(item.asString());
        }
        if (item instanceof JsonValue.Obj obj) {
            JsonValue host = obj.get("host");
            JsonValue port = obj.get("port");
            if (host == null || host.isNull() || port == null || port.isNull()) {
                throw new IllegalArgumentException("object form requires \"host\" and \"port\"");
            }
            long portNumber = port.asLong();
            if (portNumber < 1 || portNumber > 65535) {
                throw new IllegalArgumentException("port must be within [1, 65535]");
            }
            return new ProbeTarget(host.asString(), (int) portNumber);
        }
        throw new IllegalArgumentException("expected a \"host:port\" string or a {host, port} object");
    }

    private void awaitCompletion(ProbeTask task) {
        try {
            task.completion().get(config.syncMaxWaitMs(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // 任务继续运行；调用方拿到 202 以及可用于轮询的 taskId。
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.internal("interrupted while waiting for the probe task");
        } catch (java.util.concurrent.ExecutionException e) {
            throw ApiException.internal("probe task failed: " + e.getCause());
        }
    }
}
