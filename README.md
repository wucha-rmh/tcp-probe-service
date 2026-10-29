# tcp-probe-service

批量 TCP 连通性探测服务：一次请求提交多个 `host:port`，服务并发发起 TCP 握手，返回每个目标的连通状态、握手耗时与 DNS 耗时。

- 六种结果：`CONNECTED` / `REFUSED` / `TIMEOUT` / `UNRESOLVED` / `UNREACHABLE` / `ERROR`
- 只做 TCP 握手，不探测应用层协议、不发送任何业务数据
- 单次请求最多 200 个目标，结果与请求顺序一一对应
- 异步任务 + 长轮询：提交返回 `202`，查询时可带 `waitMs` 等待结果
- 两级令牌桶限流（全局 + 按客户端 IP），超限返回 `429` 与 `Retry-After`
- 每个目标有独立时间预算，DNS 解析单独限时，单个目标卡住不影响整批

## 环境与启动

需要 JDK 21+ 与 Maven 3.9+，运行时无外部依赖（不需要数据库或中间件）。

```bash
git clone https://github.com/wucha-rmh/tcp-probe-service.git
cd tcp-probe-service
mvn -q clean package
java -jar target/tcp-probe-service.jar        # 默认监听 0.0.0.0:8080
```

换端口、调参数：

```bash
java -jar target/tcp-probe-service.jar --server.port=9000 --probe.timeout-ms=1000
```

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/probes` | 提交批量探测；已完成返回 `200`，已受理返回 `202`（响应头带 `Location`） |
| `GET` | `/api/v1/probes/{taskId}?waitMs=0..10000` | 查询任务，`waitMs` 为最长轮询等待 |
| `GET` | `/api/v1/stats` | 进程内计数器：任务（`submitted`/`running`/`succeeded`/`failed`/`rejectedBusy`/`stored`）、探测（六种状态 + `attempted`/`inFlight`）、限流（`rateLimit.denied` 及按桶拆分的 `global`/`client`）、`rateLimitedClients`（当前跟踪的客户端桶数量） |
| `GET` | `/healthz` | 存活检查，只读内存不探测 |
| `GET` | `/` | 自描述索引 |

提交探测：

```bash
curl -s -H 'Content-Type: application/json' \
  -d '{"mode":"sync","targets":["example.com:443","127.0.0.1:1"]}' \
  http://localhost:8080/api/v1/probes
```

> Windows 的 PowerShell 会改写内嵌引号，建议把 JSON 存成文件：`curl.exe --data-binary "@body.json" ...`。

```json
{"taskId":"p_ab405a2012704d97","status":"SUCCEEDED","mode":"sync",
 "timeoutMs":2000,"targetCount":2,
 "summary":{"connected":1,"refused":1,"timeout":0,"unresolved":0,"unreachable":0,"error":0,"averageLatencyMs":226.0},
 "results":[{"target":"example.com:443","host":"example.com","port":443,"status":"CONNECTED","latencyMs":226,"dnsMs":59},
            {"target":"127.0.0.1:1","host":"127.0.0.1","port":1,"status":"REFUSED",
             "latencyMs":null,"dnsMs":null,"detail":"Connection refused: getsockopt"}]}
```

请求体字段：

- `targets`：必填。每项是 `"host:port"` 字符串或 `{"host":"...","port":...}` 对象；IPv6 写成 `[::1]:80`
- `mode`：`sync` 在当前请求内等结果，`async` 立即返回任务 id；省略为 `async`（查询参数 `?mode=` 也可指定，请求体优先）
- `timeoutMs`：单目标握手预算，范围 50–60000，省略用 `probe.timeout-ms`

查询任务（`async` 提交后使用）：

```bash
curl -s "http://localhost:8080/api/v1/probes/$ID?waitMs=5000"
```

任务未完成时返回 `202`，此时响应不含 `results` 与 `summary`；完成后返回 `200`。

## 探测状态

| status | 含义 | 主机可达 |
| --- | --- | --- |
| `CONNECTED` | TCP 三次握手完成 | 是 |
| `REFUSED` | 目标回 RST，端口未开放 | 是 |
| `TIMEOUT` | 连接或解析在预算内没有结果 | 未知 |
| `UNRESOLVED` | 域名无法解析 | 否 |
| `UNREACHABLE` | 网络或端口不可达 | 否 |
| `ERROR` | 其它 I/O 异常 | 未知 |

## 错误响应

统一为 `{"status":<code>,"error":{"code":"...","message":"...","details":[...]}}`，`details` 仅在批量校验失败时出现，带下标：

```json
{"status":400,"error":{"code":"INVALID_TARGETS","message":"2 of 3 targets are invalid",
 "details":["targets[1]: port must be between 1 and 65535","targets[2]: target is empty"]}}
```

`code` 取值：`MALFORMED_JSON`、`MISSING_TARGETS`、`INVALID_TARGETS`、`EMPTY_TARGETS`、`TOO_MANY_TARGETS`、`INVALID_TIMEOUT`、`INVALID_MODE`、`INVALID_QUERY`、`PAYLOAD_TOO_LARGE`、`UNSUPPORTED_MEDIA_TYPE`、`NOT_FOUND`、`METHOD_NOT_ALLOWED`、`RATE_LIMITED`、`SERVER_BUSY`、`INTERNAL_ERROR`。

状态码：

| 状态码 | 场景 |
| --- | --- |
| `200` / `202` | 任务已完成 / 已受理 |
| `400` | 参数、目标或 JSON 不合法 |
| `404` | 路径或任务不存在 |
| `405` | 方法不支持（带 `Allow` 头） |
| `413` | 请求体超限 |
| `415` | `Content-Type` 不是 JSON |
| `429` | 速率超限（带 `Retry-After`） |
| `503` | 并发任务已满，稍后重试 |

## 配置

可写在 `application.yml`、命令行（`--probe.timeout-ms=1000`）或环境变量（`PROBE_TIMEOUT_MS=1000`）。

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `probe.timeout-ms` | 2000 | 单目标 TCP 握手预算 |
| `probe.dns-timeout-ms` | 2000 | 单目标 DNS 解析预算 |
| `probe.max-in-flight` | 64 | 在途探测数，即探测线程池大小 |
| `probe.max-targets-per-request` | 200 | 单请求目标上限 |
| `probe.max-concurrent-tasks` | 16 | 并发任务上限，超出返回 503 |
| `probe.workers` | 4 | 任务线程数 |
| `probe.task-ttl-seconds` | 600 | 已完成任务在内存中的保留时长 |
| `probe.sync-max-wait-ms` | 30000 | `sync` 模式最长等待时间 |
| `probe.max-body-bytes` | 1048576 | 请求体上限，超出返回 413 |
| `probe.rate-limit.global-rps` | 50.0 | 全局令牌桶速率（requests/second） |
| `probe.rate-limit.global-burst` | 100 | 全局令牌桶突发容量 |
| `probe.rate-limit.client-rps` | 10.0 | 单客户端令牌桶速率 |
| `probe.rate-limit.client-burst` | 20 | 单客户端令牌桶突发容量 |

## 实现要点

- **探测三步**：解析（独立 DNS 线程池 + `Future.get` 限时）→ 连接（`SocketChannel.connect`，多个 A 记录共享同一份剩余预算，最多试 4 个）→ 分类（按异常类型映射状态；`REFUSED` 说明主机可达，立即停止尝试其它地址）。
- **两层调度**：任务线程池跑一批、探测线程池跑单目标；整批用 `invokeAll(calls, 批截止时间)` 施加总时限，被取消的目标输出 `TIMEOUT` 结果而不是丢弃。
- **两套「不服务」**：`429` 表示调用方速率超限，`503` 表示服务并发任务已满。
- **任务只存内存**：按 TTL 清理，进程重启即丢；需要长期留档应由调用方保存结果。
- **不做 ICMP / 应用层探测**：只回答「TCP 能否握手」，这类探测在容器与受限网络中更可靠。

## 项目结构

```
src/main/java/dev/tcpprobe/
├── ProbeApplication.java    # 启动与 Bean 装配
├── ProbeProperties.java     # probe.* 配置绑定
├── probe/
│   ├── ProbeTarget.java     # 目标解析与校验
│   ├── ProbeStatus.java     # 六种状态与语义
│   ├── ProbeResult.java
│   └── TcpProber.java       # DNS 限时、逐地址预算、错误分类
├── service/
│   ├── ProbeService.java    # 线程池、批截止、信号量、任务表
│   ├── ProbeTask.java
│   └── RateLimiter.java     # 两级令牌桶
└── web/
    ├── ProbeController.java # 端点与响应组装
    ├── ApiException.java
    ├── ApiExceptionHandler.java
    └── RequestLogFilter.java
```

测试：`mvn test`，共 49 个用例，覆盖探测器状态判定、目标校验、限流器、任务调度以及全部 HTTP 端点。
