package dev.tcpprobe.probe;

/** Outcome of a single TCP connectivity probe. */
public enum ProbeStatus {

    /** The TCP handshake completed: something is listening on the target port. */
    CONNECTED,

    /** The host answered with a TCP RST: the host is reachable but nothing listens on that port. */
    REFUSED,

    /** The handshake did not complete within the configured timeout. */
    TIMEOUT,

    /** The host name could not be resolved. */
    UNRESOLVED,

    /** The network reported that the destination cannot be reached (no route, ACL, filtering). */
    UNREACHABLE,

    /** Any other I/O or runtime error; {@code detail} carries the message. */
    ERROR;

    /** True when the probe produced a definitive answer about the target port. */
    public boolean isConclusive() {
        return this == CONNECTED || this == REFUSED;
    }

    /** True when the target is considered reachable at the network level. */
    public boolean isHostReachable() {
        return this == CONNECTED || this == REFUSED;
    }
}
