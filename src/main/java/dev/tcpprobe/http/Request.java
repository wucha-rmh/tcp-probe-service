package dev.tcpprobe.http;

import com.sun.net.httpserver.HttpExchange;
import dev.tcpprobe.error.ApiException;
import dev.tcpprobe.json.Json;
import dev.tcpprobe.json.JsonException;
import dev.tcpprobe.json.JsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable view over one inbound HTTP request, with body-limit and decoding handled once. */
public final class Request {

    private final HttpExchange exchange;
    private final Map<String, List<String>> query;
    private final byte[] body;

    Request(HttpExchange exchange, int maxBodyBytes) throws IOException {
        this.exchange = exchange;
        this.query = parseQuery(exchange.getRequestURI().getRawQuery());
        this.body = readBody(exchange, maxBodyBytes);
    }

    public String method() {
        return exchange.getRequestMethod().toUpperCase(Locale.ROOT);
    }

    public String path() {
        String p = exchange.getRequestURI().getPath();
        return p == null || p.isEmpty() ? "/" : p;
    }

    public String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    public String queryParam(String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public int queryInt(String name, int fallback, int min, int max) {
        String raw = queryParam(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_QUERY", "query parameter '" + name + "' must be an integer");
        }
        if (value < min || value > max) {
            throw ApiException.badRequest("INVALID_QUERY",
                    "query parameter '" + name + "' must be within [" + min + ", " + max + "]");
        }
        return value;
    }

    public String bodyString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Parses the body as JSON, converting syntax errors into a 400 with the parser offset. */
    public JsonValue bodyJson() {
        if (body.length == 0) {
            throw ApiException.badRequest("EMPTY_BODY", "request body must be a JSON object");
        }
        try {
            return Json.parse(bodyString());
        } catch (JsonException e) {
            throw ApiException.badRequest("MALFORMED_JSON", e.getMessage());
        }
    }

    /**
     * Address of the caller. {@code X-Forwarded-For} is only honoured when the deployment opts in,
     * because a client can otherwise trivially forge the identity used for rate limiting.
     */
    public String clientIp(boolean trustProxyHeader) {
        if (trustProxyHeader) {
            String xff = header("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        var remote = exchange.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }

    private static byte[] readBody(HttpExchange exchange, int maxBodyBytes) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] data = in.readNBytes(maxBodyBytes + 1);
            if (data.length > maxBodyBytes) {
                throw new ApiException(413, "PAYLOAD_TOO_LARGE",
                        "request body exceeds " + maxBodyBytes + " bytes");
            }
            return data;
        }
    }

    private static Map<String, List<String>> parseQuery(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.computeIfAbsent(decode(key), k -> new java.util.ArrayList<>(1)).add(decode(value));
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
