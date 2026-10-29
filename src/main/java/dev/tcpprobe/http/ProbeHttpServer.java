package dev.tcpprobe.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.tcpprobe.config.Config;
import dev.tcpprobe.error.ApiException;
import dev.tcpprobe.http.handler.HealthHandler;
import dev.tcpprobe.http.handler.RootHandler;
import dev.tcpprobe.http.handler.StatsHandler;
import dev.tcpprobe.http.handler.SubmitProbeHandler;
import dev.tcpprobe.http.handler.TaskHandler;
import dev.tcpprobe.json.Json;
import dev.tcpprobe.json.JsonException;
import dev.tcpprobe.service.Metrics;
import dev.tcpprobe.service.ProbeService;
import dev.tcpprobe.service.RateLimiter;
import dev.tcpprobe.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP 前端：绑定 JDK 自带的 {@link HttpServer}，注册路由，并负责每个端点共享的横切关注点
 * ——请求 id、计时、错误到 JSON 的映射、指标与日志。
 *
 * <p>使用 JDK 自带的服务器而不是外部框架是刻意的取舍：它是唯一保证存在的 HTTP 服务器
 * （无需在企业代理后面下载 artifact），而这里的 API 足够小，一个路由器就构成了我们需要的全部框架。</p>
 */
public final class ProbeHttpServer implements AutoCloseable {

    private final Config config;
    private final Metrics metrics;
    private final Router router;
    private final AtomicBoolean started = new AtomicBoolean();
    private HttpServer server;
    private ExecutorService executor;

    public ProbeHttpServer(Config config, ProbeService service, RateLimiter rateLimiter, Metrics metrics) {
        this.config = config;
        this.metrics = metrics;
        this.router = new Router()
                .get("/", new RootHandler(config))
                .get("/healthz", new HealthHandler(config, metrics))
                .get("/api/v1/stats", new StatsHandler(metrics))
                .post("/api/v1/probes", new SubmitProbeHandler(config, service, rateLimiter))
                .get("/api/v1/probes/{taskId}", new TaskHandler(service));
    }

    public ProbeHttpServer start() throws IOException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("server already started");
        }
        InetSocketAddress address = new InetSocketAddress(
                InetAddress.getByName(config.bindAddress()), config.port());
        HttpServer created = HttpServer.create(address, 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        created.setExecutor(executor);
        created.createContext("/", this::handle);
        created.start();
        this.server = created;
        return this;
    }

    /** 实际绑定的端口；在请求了 {@code server.port=0} 时有用。 */
    public int port() {
        HttpServer current = server;
        return current == null ? -1 : current.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) {
        long startedNanos = System.nanoTime();
        String requestId = "r_" + Long.toHexString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE);
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        exchange.getResponseHeaders().set("X-Request-Id", requestId);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        metrics.httpRequest();

        int status = 500;
        // 在分发之前构建，这样在*读取请求*期间抛出的失败
        //（413 请求体过大、400 查询参数错误）仍然能渲染成 JSON。
        Response response = new Response();
        try {
            try {
                Request request = new Request(exchange, config.maxRequestBodyBytes());
                router.dispatch(request, response);
            } catch (ApiException e) {
                recordClientErrorMetrics(e.status());
                if (e.retryAfterSeconds() > 0) {
                    response.header("Retry-After", Long.toString(e.retryAfterSeconds()));
                }
                response.json(e.status(), Views.error(e));
            }
            status = response.status();
            write(exchange, response);
        } catch (JsonException e) {
            status = 400;
            metrics.httpBadRequest();
            writeError(exchange, 400, "MALFORMED_JSON", e.getMessage());
        } catch (IOException e) {
            // 客户端已断开（或请求体无法读取）。已经没有需要回复的对象了。
            status = 499;
            Log.debug("client disconnected during %s %s: %s", method, path, e.getMessage());
        } catch (Exception e) {
            status = 500;
            metrics.httpServerError();
            Log.error("unhandled error serving " + method + " " + path + " (" + requestId + ")", e);
            writeError(exchange, 500, "INTERNAL_ERROR", "unexpected server error; request id " + requestId);
        } finally {
            long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
            logRequest(method, path, status, durationMs);
            exchange.close();
        }
    }

    private void recordClientErrorMetrics(int status) {
        if (status == 429) {
            metrics.httpRateLimited();
        } else if (status >= 400 && status < 500) {
            metrics.httpBadRequest();
        }
    }

    private static void logRequest(String method, String path, int status, long durationMs) {
        if (status >= 500) {
            Log.warn("%s %s -> %d (%d ms)", method, path, status, durationMs);
        } else if (status == 429) {
            Log.debug("%s %s -> 429 (%d ms)", method, path, durationMs);
        } else if (status >= 400) {
            Log.info("%s %s -> %d (%d ms)", method, path, status, durationMs);
        } else {
            Log.debug("%s %s -> %d (%d ms)", method, path, status, durationMs);
        }
    }

    private static void write(HttpExchange exchange, Response response) throws IOException {
        Object payload = response.payload();
        byte[] body = payload == null
                ? new byte[0]
                : Json.write(payload).getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> header : response.headers().entrySet()) {
            exchange.getResponseHeaders().set(header.getKey(), header.getValue());
        }
        if (body.length > 0) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static void writeError(HttpExchange exchange, int status, String code, String message) {
        try {
            if (exchange.getResponseCode() != -1) {
                return; // 已经有人开始写响应了
            }
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            Map<String, Object> error = new java.util.LinkedHashMap<>();
            error.put("code", code);
            error.put("message", message == null || message.isBlank() ? code : message);
            body.put("status", status);
            body.put("error", error);
            Response response = new Response().json(status, body);
            write(exchange, response);
        } catch (IOException e) {
            Log.debug("could not send error response: %s", e.getMessage());
        }
    }

    /** 优雅停止：{@code delaySeconds} 让在途的 exchange 在 socket 关闭之前完成。 */
    public void stop(int delaySeconds) {
        HttpServer current = server;
        if (current != null) {
            current.stop(delaySeconds);
        }
        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) {
            currentExecutor.shutdown();
            try {
                currentExecutor.awaitTermination(Math.max(1, delaySeconds), java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop(0);
    }
}
