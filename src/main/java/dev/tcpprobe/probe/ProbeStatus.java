package dev.tcpprobe.probe;

/**
 * 单次 TCP 连通性探测的结果。
 *
 * <p>刻意区分“主机能不能到”和“端口有没有人听”：{@code REFUSED} 说明主机可达、端口关闭，
 * 这往往是排查时最有用的信息，把它和 {@code TIMEOUT} 混作一谈会丢掉这个信号。</p>
 */
public enum ProbeStatus {

    /** TCP 握手完成：目标端口上确实有东西在监听。 */
    CONNECTED,

    /** 主机以 TCP RST 作答：主机可达，但该端口没有监听者。 */
    REFUSED,

    /** 握手没有在预算内完成。 */
    TIMEOUT,

    /** 主机名无法解析。 */
    UNRESOLVED,

    /** 网络报告目的地不可达（无路由、ACL、过滤）。 */
    UNREACHABLE,

    /** 其他 I/O 或运行时错误，{@code detail} 携带消息。 */
    ERROR;

    /** 本次探测是否对目标端口给出了确定性答案。 */
    public boolean isConclusive() {
        return this == CONNECTED || this == REFUSED;
    }

    /** 目标主机在网络层面是否可达。 */
    public boolean isHostReachable() {
        return this == CONNECTED || this == REFUSED;
    }
}
