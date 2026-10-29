package dev.tcpprobe.http.handler;

import dev.tcpprobe.config.Config;
import dev.tcpprobe.http.Handler;
import dev.tcpprobe.http.Request;
import dev.tcpprobe.http.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code GET /}: a self-describing index, handy when curling a fresh deployment. */
public final class RootHandler implements Handler {

    private final Config config;

    public RootHandler(Config config) {
        this.config = config;
    }

    @Override
    public void handle(Request request, Response response, Map<String, String> pathParams) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", "tcp-probe-service");
        out.put("version", config.version());
        out.put("endpoints", List.of(
                "GET  /healthz",
                "GET  /api/v1/stats",
                "POST /api/v1/probes",
                "GET  /api/v1/probes/{taskId}?waitMs=3000"));
        out.put("docs", "README.md");
        response.json(out);
    }
}
