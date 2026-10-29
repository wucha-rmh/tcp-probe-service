package dev.tcpprobe.json;

/** 当 JSON 文档无法解析或与预期结构不符时抛出。 */
public class JsonException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
