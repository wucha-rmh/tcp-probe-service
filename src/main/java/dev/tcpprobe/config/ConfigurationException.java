package dev.tcpprobe.config;

/** 当运行时配置非法或自相矛盾时抛出。 */
public class ConfigurationException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
