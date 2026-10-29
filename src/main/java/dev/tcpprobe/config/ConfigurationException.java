package dev.tcpprobe.config;

/** Raised when the runtime configuration is invalid or contradictory. */
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
