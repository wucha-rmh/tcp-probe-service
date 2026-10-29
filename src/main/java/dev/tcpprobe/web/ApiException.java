package dev.tcpprobe.web;

import java.io.Serial;
import java.util.List;

/**
 * 直接映射到一条 HTTP 响应的错误。
 *
 * <p>服务层抛出它，而不是返回错误码，处理器因此不需要写分支判断；
 * 只有 {@link ApiExceptionHandler} 知道状态码与 JSON 外壳长什么样。</p>
 */
public class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final List<String> details;
    private final Integer retryAfterSeconds;

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of(), null);
    }

    public ApiException(int status, String code, String message, List<String> details) {
        this(status, code, message, details, null);
    }

    public ApiException(int status, String code, String message, List<String> details, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<String> details() {
        return details;
    }

    /** {@code Retry-After} 头的值；不适用时为 {@code null}。 */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException badRequest(String code, String message, List<String> details) {
        return new ApiException(400, code, message, details);
    }

    public static ApiException payloadTooLarge(long maxBytes) {
        return new ApiException(413, "PAYLOAD_TOO_LARGE", "request body exceeds " + maxBytes + " bytes");
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    public static ApiException tooManyRequests(String message, long retryAfterSeconds) {
        return new ApiException(429, "RATE_LIMITED", message, List.of(),
                (int) Math.max(1L, Math.min(Integer.MAX_VALUE, retryAfterSeconds)));
    }

    public static ApiException serverBusy() {
        return new ApiException(503, "SERVER_BUSY", "too many concurrent probe tasks, retry later");
    }
}
