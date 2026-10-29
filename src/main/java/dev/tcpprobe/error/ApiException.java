package dev.tcpprobe.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Error that maps directly onto an HTTP response.
 *
 * <p>The service layer throws this instead of returning error codes, so that handlers stay free of
 * branching. The HTTP layer is the only place that knows about status codes and the JSON envelope.</p>
 */
public class ApiException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    /** Owned copy, kept as a concrete serializable type so the exception itself stays serializable. */
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

    /** Value for the {@code Retry-After} header, or {@code -1} when not applicable. */
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
