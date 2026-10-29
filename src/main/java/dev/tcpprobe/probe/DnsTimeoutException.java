package dev.tcpprobe.probe;

/** Raised when name resolution exceeds its budget; mapped by the prober onto a {@code TIMEOUT}. */
public class DnsTimeoutException extends Exception {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public DnsTimeoutException(String message) {
        super(message);
    }
}
