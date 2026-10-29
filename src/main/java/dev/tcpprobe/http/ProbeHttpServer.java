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
 * HTTP front end: binds the JDK's built-in {@link HttpServer}, registers the routes and owns the
 * cross-cutting concerns every endpoint shares — request ids, timing, error-to-JSON mapping,
 * metrics and logging.
 *
 * <p>Using the JDK server instead of an external framework is a deliberate trade-off: it is the only
 * HTTP server that is guaranteed to be there (no artifact download behind a corporate proxy), and
 * the API here is small enough that a router is all the framework we need.</p>
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

    /** Actual bound port; useful when {@code server.port=0} was requested. */
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
        // Built before dispatch so failures raised while *reading the request*
        // (413 body too large, 400 bad query) can still be rendered as JSON.
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
            // The client hung up (or the body could not be read). Nobody left to answer to.
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
                return; // someone already started the response
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

    /** Graceful stop: {@code delaySeconds} lets in-flight exchanges finish before the socket closes. */
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
