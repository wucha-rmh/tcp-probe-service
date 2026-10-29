package dev.tcpprobe.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 直接映射到一条 HTTP 响应的错误。
 *
 * <p>服务层抛出它而不是返回错误码，从而让处理器不必写分支判断。
 * HTTP 层是唯一了解状态码与 JSON 外层结构的代码。</p>
 */
public class ApiException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    /** 自己持有的副本，保持为具体的可序列化类型，以便异常本身保持可序列化。 */
    private final ArrayList<String> details;
    private final long retryAfterSeconds;

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of(), -1);
    }

    public ApiException(int status, String code, String message, List<String> details) {
        this(status, code, message, details, -1);
    }

    private ApiException(int status, String code, String message, List<String> details, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? new ArrayList<>() : new ArrayList<>(details);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<String> details() {
        return Collections.unmodifiableList(details);
    }

    /** {@code Retry-After} 响应头的取值，不适用时为 {@code -1}。 */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException badRequest(String code, String message, List<String> details) {
        return new ApiException(400, code, message, details);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    public static ApiException methodNotAllowed(String method, String path) {
        return new ApiException(405, "METHOD_NOT_ALLOWED", method + " is not supported for " + path);
    }

    public static ApiException tooManyRequests(String message, long retryAfterSeconds) {
        return new ApiException(429, "RATE_LIMITED", message, List.of(), Math.max(1, retryAfterSeconds));
    }

    public static ApiException serverBusy(String message) {
        return new ApiException(503, "SERVER_BUSY", message);
    }

    public static ApiException internal(String message) {
        return new ApiException(500, "INTERNAL_ERROR", message);
    }
}
