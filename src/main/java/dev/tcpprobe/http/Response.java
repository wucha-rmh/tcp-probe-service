package dev.tcpprobe.http;

import java.util.LinkedHashMap;
import java.util.Map;

/** 由 {@link Handler} 填充的可变响应累加器。 */
public final class Response {

    private int status = 200;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private Object payload;

    public int status() {
        return status;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Object payload() {
        return payload;
    }

    public Response status(int newStatus) {
        this.status = newStatus;
        return this;
    }

    public Response header(String name, String value) {
        if (value != null) {
            headers.put(name, value);
        }
        return this;
    }

    /** 设置 JSON 响应体；稍后由 {@link dev.tcpprobe.json.Json#write(Object)} 渲染。 */
    public Response json(int newStatus, Object body) {
        this.status = newStatus;
        this.payload = body;
        return this;
    }

    public Response json(Object body) {
        this.payload = body;
        return this;
    }
}
