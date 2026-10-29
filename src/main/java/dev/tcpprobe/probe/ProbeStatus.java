package dev.tcpprobe.probe;

/** 单次 TCP 连通性探测的结果。 */
public enum ProbeStatus {

    /** TCP 握手完成：目标端口上确实有东西在监听。 */
    CONNECTED,

    /** 主机以 TCP RST 作答：主机可达，但该端口上没有任何监听者。 */
    REFUSED,

    /** 握手未在配置的超时内完成。 */
    TIMEOUT,

    /** 主机名无法解析。 */
    UNRESOLVED,

    /** 网络报告目的地不可达（无路由、ACL、过滤）。 */
    UNREACHABLE,

    /** 其他任意 I/O 或运行时错误；{@code detail} 携带消息。 */
    ERROR;

    /** 当探测对目标端口给出了确定性答案时为 true。 */
    public boolean isConclusive() {
        return this == CONNECTED || this == REFUSED;
    }

    /** 当目标在网络层面被视为可达时为 true。 */
    public boolean isHostReachable() {
        return this == CONNECTED || this == REFUSED;
    }
}
