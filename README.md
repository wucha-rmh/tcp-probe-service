# tcp-probe-service

一个 TCP 连通性探测 HTTP 服务：接收批量探测任务，在后台对目标做 TCP 握手探测，返回每个目标的连通状态与耗时。

基础设施交给 Spring Boot（HTTP 路由、JSON 序列化、配置绑定、优雅停机），**探测逻辑自己写**，所以核心代码集中在
`probe/TcpProber.java` 的 220 行里：DNS 限时、逐地址共享预算、错误分类，全部可读可改，没有封装好的探测组件。
主代码共 13 个文件、约 1200 行。

- **批量 + 批级截止时间**：一个请求最多 200 个目标，整批共享一个时间预算，单个目标卡住不会拖垮整批。
- **DNS 单独限时**：`InetAddress.getAllByName` 自己不会超时，用固定线程池 + `Future.get(timeout)` 兜住。
- **两级令牌桶限流**：全局 + 按客户端 IP，拒绝时返回 `429` 和 `Retry-After`。
- **异步任务 + 长轮询**：`async` 提交立即返回 `202`，`sync` 只是在同一条执行路径上等结果。

## 快速开始

需要 JDK 21+ 与 Maven 3.9+。

```bash
git clone <this-repo>
cd tcp-probe-service
mvn -q clean package          # 产物 target/tcp-probe-service.jar（Spring Boot 可执行 jar）
java -jar target/tcp-probe-service.jar
```

> Maven 需要联网下载依赖；离线环境可加 `-o`，只想先跑起来可以加 `-DskipTests`。

默认监听 `0.0.0.0:8080`：

```
2026-10-29T07:34:20.112Z  INFO 38412 --- [main] d.tcpprobe.ProbeApplication : Started ProbeApplication in 1.284 seconds
```

换端口：`java -jar target/tcp-probe-service.jar --server.port=9000`；
改探测参数：`--probe.timeout-ms=1000 --probe.rate-limit.client.rps=2`。

## 验证

```bash
# 1) 活着
curl -s localhost:8080/healthz

# 2) 同步批量：一个通、一个拒连、一个超时、一个解析不出来
cat > /tmp/batch.json <<'JSON'
{"mode":"sync","targets":["example.com:443","127.0.0.1:1","192.0.2.1:9","no-such-host.invalid:80"]}
JSON
curl -s -H 'Content-Type: application/json' --data-binary @/tmp/batch.json localhost:8080/api/v1/probes
```

期望看到四个结果，状态依次是 `CONNECTED`、`REFUSED`、`TIMEOUT`、`TIMEOUT`（最后一条的 `detail` 是
`dns resolution timed out after 2000 ms`），整批约 2 秒返回，`summary` 里是各状态计数：

```json
{"taskId":"p_bc7edb20f2454f10","status":"SUCCEEDED","mode":"sync",
 "summary":{"connected":1,"refused":1,"timeout":2,"unresolved":0,"unreachable":0,"error":0,"averageLatencyMs":228.0},
 "results":[{"target":"example.com:443","status":"CONNECTED","latencyMs":228,"dnsMs":262},
            {"target":"127.0.0.1:1","status":"REFUSED","latencyMs":null,"detail":"Connection refused: getsockopt"},
            {"target":"192.0.2.1:9","status":"TIMEOUT","latencyMs":null,"detail":"connect timed out after 1999 ms"},
            {"target":"no-such-host.invalid:80","status":"TIMEOUT","latencyMs":null,
             "detail":"dns resolution timed out after 2000 ms"}]}
```

```bash
# 3) 异步 + 长轮询
ID=$(curl -s -H 'Content-Type: application/json' -d '{"targets":["example.com:443"]}' \
     localhost:8080/api/v1/probes | jq -r .taskId)      # 返回 202 + Location
curl -s "localhost:8080/api/v1/probes/$ID?waitMs=5000"  # 结果出来就立即返回，未完成最多等 5s

# 4) 限流：客户端突发额度用完后开始 429
for i in $(seq 1 25); do curl -s -o /dev/null -w '%{http_code} ' \
     -H 'Content-Type: application/json' -d '{"targets":["127.0.0.1:1"]}' localhost:8080/api/v1/probes; done; echo
# 202 202 … 429，429 带 Retry-After

# 5) 指标
curl -s localhost:8080/api/v1/stats
```

## 探测是怎么做的

`probe/TcpProber.java` 里一次探测就三步，每一步都有独立预算：

1. **解析**：目标是 IP 字面量就跳过；否则把解析丢进固定大小的 DNS 池，
   `future.get(dnsTimeoutMs)` 等结果，超时抛 `DnsTimeoutException` → 判定 `TIMEOUT`。
   为什么这么绕：JDK 的解析器不响应中断，直接调用会让整批任务卡在 DNS 上（实测一个 `.invalid` 域名
   能把批处理拖到十几秒）。池子大小本身就是 DNS 的背压。
2. **连接**：`SocketChannel.open()` + `socket().connect(address, remainingMs)`，`remainingMs` 是
   「本次探测的剩余预算」，多个 A 记录共享同一个预算，不会因为逐个重试而线性放大耗时。最多试 4 个地址。
3. **分类**：`SocketTimeoutException` → `TIMEOUT`，`PortUnreachable`/`NoRouteToHost` → `UNREACHABLE`，
   其余 `SocketException` 按消息归类（`refused` → `REFUSED`，`unreachable`/`no route` → `UNREACHABLE`）。
   `REFUSED` 说明主机可达、只是端口没开，所以立刻停止重试其它地址。

外层再套两层调度：

- `ProbeService` 用 `probePool.invokeAll(calls, batchDeadline)` 给整批设截止时间；被取消的目标（还没轮到）
  直接产出 `TIMEOUT` 结果而不是丢失，**返回顺序始终与请求顺序一致**，调用方不用自己对齐。
- `task.max-concurrent` 信号量限制同时在跑的任务数，满了返回 `503 SERVER_BUSY`（与限流的 `429` 区分：
  后者是「你打太快」，前者是「服务整体忙」）。

状态语义：

| status | 含义 | 主机可达 |
| --- | --- | --- |
| `CONNECTED` | TCP 三次握手完成 | 是 |
| `REFUSED` | 目标回了 RST，端口没开 | 是 |
| `TIMEOUT` | 连接或 DNS 在预算内没结果 | 未知 |
| `UNRESOLVED` | 域名确实不存在 | 否 |
| `UNREACHABLE` | 网络不可达 / 端口不可达 | 否 |
| `ERROR` | 其它 I/O 异常 | 未知 |

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/probes` | 提交批量探测。`200` 表示已完成，`202` 表示已入队（响应头 `Location`） |
| `GET` | `/api/v1/probes/{taskId}?waitMs=0..10000` | 查询任务，长轮询 |
| `GET` | `/api/v1/stats` | 进程内计数器 |
| `GET` | `/healthz` | 存活探针，只查内存 |

每个请求留一行日志：`POST /api/v1/probes -> 200 (488 ms)`（5xx 为 WARN）。

请求体：

```json
{
  "targets": ["example.com:443", "127.0.0.1:8080", {"host": "10.0.0.1", "port": 22}],
  "mode": "sync",
  "timeoutMs": 1500
}
```

`targets` 每项可以是 `host:port` 字符串或 `{host, port}` 对象（IPv6 写成 `[::1]:80`）；
`mode` 省略为 `async`；`timeoutMs` 省略用 `probe.timeout-ms`。

错误统一为 `{"status": <code>, "error": {"code": "...", "message": "...", "details": [...]}}`，
`code` 覆盖 `MALFORMED_JSON`、`INVALID_TARGETS`、`EMPTY_TARGETS`、`TOO_MANY_TARGETS`、`INVALID_TIMEOUT`、
`INVALID_MODE`、`RATE_LIMITED`、`SERVER_BUSY`、`PAYLOAD_TOO_LARGE`、`NOT_FOUND`、`INVALID_QUERY`。

## 配置

全部可写在 `application.yml`、命令行（`--probe.timeout-ms=1000`）或环境变量（`PROBE_TIMEOUT_MS=1000`）。

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `probe.timeout-ms` | 2000 | 单次 TCP 握手预算 |
| `probe.dns-timeout-ms` | 2000 | 单次 DNS 解析预算 |
| `probe.max-in-flight` | 64 | 在途探测数 = 探测线程池大小 |
| `probe.max-targets-per-request` | 200 | 单请求目标上限 |
| `probe.max-concurrent-tasks` | 16 | 并发任务上限，超出 503 |
| `probe.workers` | 4 | 任务线程数 |
| `probe.task-ttl-seconds` | 600 | 已完成任务保留时长 |
| `probe.sync-max-wait-ms` | 30000 | sync 模式最长等待 |
| `probe.max-body-bytes` | 1048576 | 请求体上限，超出 413 |
| `probe.rate-limit.*` | 50rps/100、10rps/20 | 全局与单客户端令牌桶 |

## 代码结构

```
src/main/java/dev/tcpprobe/
├── ProbeApplication.java      # 启动 + TcpProber bean
├── ProbeProperties.java       # probe.* 配置，含批截止时间估算
├── probe/                     # 探测核心
│   ├── ProbeTarget.java       # target 解析与校验
│   ├── ProbeStatus.java       # 六种状态与语义
│   ├── ProbeResult.java
│   └── TcpProber.java         # DNS 限时 + 逐地址预算 + 错误分类
├── service/
│   ├── ProbeService.java      # 线程池、批截止、信号量、任务表
│   ├── ProbeTask.java
│   └── RateLimiter.java       # 两级令牌桶
└── web/
    ├── ProbeController.java   # 四个端点 + 视图组装
    ├── ApiException.java
    └── ApiExceptionHandler.java
```

## 测试

```bash
mvn -q test      # 49 个用例，约 3 秒
```

覆盖：`ProbeTarget` 的 11 种非法输入、`TcpProber` 对真监听端口/关闭端口/不存在的域名/DNS 卡死的判定、
`ProbeService` 的顺序保持与并发上限、`RateLimiter` 的回填与全局/客户端隔离，以及 13 个端到端 HTTP 用例
（真启动 Spring 容器，用 `TestRestTemplate` 打进来）。

## 取舍

- 任务状态只在内存里，进程重启即丢。这个服务是「探测一次、拿结果」的工具，不需要持久化；要长期留档应当把
  结果写进调用方自己的存储。
- 探测只用 TCP 握手，不做应用层（HTTP/Redis）协议探测，也不做 ICMP——题目要的是连通性，ICMP 在容器里常被限制。
- 目标列表、超时参数都在请求里，没有做「探测模板」之类的功能，保持接口小。
