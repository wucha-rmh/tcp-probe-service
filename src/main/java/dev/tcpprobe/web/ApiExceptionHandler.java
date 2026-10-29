package dev.tcpprobe.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把所有异常收敛成同一个 JSON 错误结构：{@code {"status": n, "error": {"code", "message", "details"}}}。
 *
 * <p>Spring 自带的错误页结构不同，所以这里整体接管：客户端只需要认一种错误格式。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.status());
        if (e.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        return builder.body(error(e.status(), e.code(), e.getMessage(), e.details()));
    }

    /** 请求体不是合法 JSON，或类型对不上（例如 targets 里塞了数字）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(error(400, "MALFORMED_JSON", "request body is not valid JSON: " + rootMessage(e), List.of()));
    }

    /** 查询参数格式不对，例如 {@code ?waitMs=soon}。 */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> handleBadQuery(Exception e) {
        return ResponseEntity.badRequest()
                .body(error(400, "INVALID_QUERY", "query parameter is not valid: " + rootMessage(e), List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoRoute(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(404, "NOT_FOUND", "no route for /" + e.getResourcePath(), List.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (e.getSupportedHttpMethods() != null) {
            builder.allow(e.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return builder.body(error(405, "METHOD_NOT_ALLOWED",
                "method " + e.getMethod() + " is not supported for this path", List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(error(415, "UNSUPPORTED_MEDIA_TYPE",
                        "request body must be application/json", List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("unhandled error", e);
        return ResponseEntity.status(HttpStatusCode.valueOf(500))
                .body(error(500, "INTERNAL_ERROR", "unexpected server error", List.of()));
    }

    private static Map<String, Object> error(int status, String code, String message, List<String> details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (details != null && !details.isEmpty()) {
            error.put("details", details);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", error);
        return body;
    }

    private static String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
