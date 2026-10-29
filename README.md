# tcp-probe-service

一个零第三方运行时依赖的 TCP 连通性探测 HTTP 服务：接单、批量探测、限流、查询结果、看指标。

- **只依赖 JDK 21**（HTTP 服务用 `com.sun.net.httpserver`，连接用 `java.nio.channels.SocketChannel`）。
  题目要求「不要使用第三方封装好的探测组件」，所以连 HTTP 框架、JSON 库、连接池都是自己写的——加起来约 3.5k 行，全部可读可改。
- **批量探测 + 批级截止时间**：一个请求最多 200 个目标，整批共享一个时间预算，不会因为某一个目标卡住而拖垮整批。
- **两级令牌桶限流**：全局 + 按客户端 IP，拒绝时返回 `429` 和 `Retry-After`。
- **异步任务模型 + 长轮询**：`async` 提交立即返回 `202`，`sync` 只是同一个执行路径上等结果。
- **完整可观测性**：每个请求带 `X-Request-Id`、结构化单行日志、`/api/v1/stats` 计数器。

> 设计决策与取舍记录在 [docs/design.md](docs/design.md)，面试时可以直接按那份文档讲每一条为什么这么做。

## 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21+（开发用 25） | 用到的 API 全部在 JDK 21 就有 |
| Maven | 3.9+ | 仅构建/测试需要；**运行时 jar 没有任何第三方依赖** |

## 快速开始

```bash
git clone <this-repo>
cd tcp-probe-service
mvn -q clean package          # 产物：target/tcp-probe-service.jar
java -jar target/tcp-probe-service.jar
```

看到这两行就说明起来了（默认监听 `0.0.0.0:8080`）：

```
2026-10-29T07:00:00.000Z INFO  tcp-probe-service 1.0.0 listening on http://0.0.0.0:8080
2026-10-29T07:00:00.000Z INFO  configuration: {bind=0.0.0.0:8080, probeTimeoutMs=2000, …}
```

换端口、开调试日志、指定配置文件：

```bash
java -jar target/tcp-probe-service.jar --server.port=9000 --log.level=DEBUG
java -jar target/tcp-probe-service.jar --config=config/application.properties
```

## 验证（拉下来 5 分钟能跑通）

下面每条命令都可以直接粘贴执行；`$BASE` 按实际端口改。

```bash
export BASE=http://127.0.0.1:8080
```

**1. 健康检查与根路由**

```bash
curl -s $BASE/healthz
# {"status":"ok","service":"tcp-probe-service","version":"1.0.0","uptimeSeconds":3,...}

curl -s $BASE/
# {"service":"tcp-probe-service","version":"1.0.0","endpoints":[...],"docs":"README.md"}
```

**2. 同步探测一批目标（结果直接返回）**

```bash
curl -s -X POST $BASE/api/v1/probes \
  -H 'Content-Type: application/json' \
  -d '{
        "targets": ["example.com:443", "127.0.0.1:65000", "192.0.2.1:9"],
        "timeoutMs": 1000,
        "mode": "sync"
      }'
```

预期：`200`，`status` 为 `SUCCEEDED`，`results` 顺序与请求一致。典型输出（本机实测，数据随网络变化）：

```json
{
  "taskId": "p_1f0c…",
  "status": "SUCCEEDED",
  "mode": "sync",
  "timeoutMs": 1000,
  "targetCount": 3,
  "summary": {"connected":1,"refused":1,"timeout":1,"unresolved":0,"unreachable":0,"error":0,"averageLatencyMs":42.5},
  "results": [
    {"target":"example.com:443","host":"example.com","port":443,"status":"CONNECTED","latencyMs":85,"dnsMs":1},
    {"target":"127.0.0.1:65000","host":"127.0.0.1","port":65000,"status":"REFUSED","detail":"Connection refused: getsockopt"},
    {"target":"192.0.2.1:9","host":"192.0.2.1","port":9,"status":"TIMEOUT","detail":"connect timed out after 1000 ms"}
  ]
}
```

**3. 异步探测 + 长轮询**

```bash
ID=$(curl -s -X POST $BASE/api/v1/probes \
      -H 'Content-Type: application/json' \
      -d '{"targets":["example.com:80","1.1.1.1:53"],"timeoutMs":800}' \
      | sed -E 's/.*"taskId":"([^"]+)".*/\1/')

curl -s "$BASE/api/v1/probes/$ID?waitMs=3000"
```

预期：提交时 `202` + `Location: /api/v1/probes/<id>`；带 `waitMs` 的查询会一直等到任务完成（最多 10s），返回 `200` 和完整结果。

**4. 限流**

```bash
for i in $(seq 1 5); do
  curl -s -o /dev/null -w "%{http_code} " -X POST $BASE/api/v1/probes \
       -H 'Content-Type: application/json' -d '{"targets":["example.com:80"]}'
done
```

默认配额是每 IP 10 rps / 突发 20，所以这 5 次不会被拒；把 `--rate-limit.client.rps=0.2 --rate-limit.client.burst=2` 加在启动参数上再跑，会看到 `202 202 429 429 429`，且 `429` 响应带 `Retry-After` 与 `X-RateLimit-Remaining: 0`。

**5. 错误路径**

```bash
curl -s -X POST $BASE/api/v1/probes -H 'Content-Type: application/json' -d '{not json}'
# 400 MALFORMED_JSON

curl -s -X POST $BASE/api/v1/probes -H 'Content-Type: application/json' -d '{"targets":["no-such-host.invalid:80"]}'
# 结果里 status=TIMEOUT/UNRESOLVED，取决于本机 DNS（见下文「DNS 也要限时」）

curl -s $BASE/api/v1/probes/p_missing           # 404 NOT_FOUND
curl -s "$BASE/api/v1/probes/p_missing?waitMs=x"  # 400 INVALID_QUERY
curl -s -X DELETE $BASE/api/v1/probes/p_missing # 405 + Allow: GET
curl -s $BASE/api/v1/stats                      # 计数器快照
```

**6. 跑测试**

```bash
mvn verify
```

75 个测试，全部是确定性的（不访问外网）：单元测试 + 用真实 socket 起服务的端到端测试。

## API 参考

所有响应都是 `application/json; charset=utf-8`，错误统一信封：

```json
{"status": 400, "error": {"code": "INVALID_TARGETS", "message": "…", "details": ["targets[0] …"]}}
```

### `POST /api/v1/probes`

提交一次批量探测。

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `targets` | array | 是 | — | 最多 `probe.max-targets-per-request`（默认 200）个；元素可以是 `"host:port"` 字符串，或 `{"host":"…","port":443}` |
| `mode` | string | 否 | `async` | `sync`（在本请求内等结果）或 `async`（立即返回任务 id）。也可用查询参数 `?mode=sync` 指定，请求体优先级更高 |
| `timeoutMs` | int | 否 | `probe.timeout-ms`（2000） | 单次连接超时，范围 `[50, 60000]` |

响应：`200`（`sync` 且已出结果）/ `202`（排队或执行中），`Location` 头指向任务；响应头带 `X-RateLimit-Remaining`。

### `GET /api/v1/probes/{taskId}`

查询任务。已完成返回 `200`，未完成返回 `202`。`?waitMs=N` 长轮询最多 N 毫秒（上限 10000），用于避免客户端忙轮询。

任务 JSON 结构：

```json
{
  "taskId": "p_…", "status": "PENDING|RUNNING|SUCCEEDED|FAILED",
  "mode": "sync|async", "createdAt": "…", "startedAt": "…|null", "finishedAt": "…|null",
  "timeoutMs": 2000, "targetCount": 2,
  "summary": {"connected":1,"refused":1,"timeout":0,"unresolved":0,"unreachable":0,"error":0,"averageLatencyMs":42.5},
  "results": [{"target":"example.com:443","host":"example.com","port":443,
               "status":"CONNECTED","latencyMs":85,"dnsMs":1}]
}
```

`summary` 与 `results` 只在任务 `SUCCEEDED`/`FAILED` 后出现，避免把半成品当最终结果。

### `GET /healthz`

只读内存状态，不发起任何探测，可以放心给探活/负载均衡器用。

### `GET /api/v1/stats`

计数器快照：`http{requests,badRequests,rateLimited,serverErrors}`、`tasks{submitted,succeeded,failed,expired,running}`、`probes{attempted,connected,refused,timeout,unresolved,unreachable,error}`、`probesInFlight`。

### 探测状态语义

| 状态 | 含义 | 判定依据 |
| --- | --- | --- |
| `CONNECTED` | 端口有服务在听 | TCP 三次握手完成 |
| `REFUSED` | 主机可达，端口没开 | `RST`（`ECONNREFUSED`）。**这已经证明主机在线**，所以不再尝试该主机的其他地址 |
| `TIMEOUT` | 超时 | 连接或 DNS 在预算内没有结论 |
| `UNRESOLVED` | 域名解析失败 | `UnknownHostException` |
| `UNREACHABLE` | 网络层不通 | no route to host / host unreachable / port unreachable |
| `ERROR` | 其他异常 | 例如本机 socket 资源不足 |

### HTTP 状态码

| 码 | 场景 | `error.code` |
| --- | --- | --- |
| 200 | `sync` 探测完成 / 任务已完成 | — |
| 202 | 任务排队或执行中 | — |
| 400 | 请求体或参数不合法 | `MALFORMED_JSON` `INVALID_BODY` `MISSING_TARGETS` `INVALID_TARGETS` `EMPTY_TARGETS` `TOO_MANY_TARGETS` `INVALID_MODE` `INVALID_TIMEOUT` `INVALID_QUERY` `EMPTY_BODY` |
| 404 | 任务不存在 / 路由不存在 | `NOT_FOUND` |
| 405 | 方法不支持（带 `Allow` 头） | `METHOD_NOT_ALLOWED` |
| 413 | 请求体超过 `http.max-body-bytes` | `PAYLOAD_TOO_LARGE` |
| 415 | `Content-Type` 不是 JSON | `UNSUPPORTED_MEDIA_TYPE` |
| 429 | 触发限流（带 `Retry-After`） | `RATE_LIMITED` |
| 500 | 未预期异常（带 `X-Request-Id` 便于对日志） | `INTERNAL_ERROR` |
| 503 | 并发任务数达到 `task.max-concurrent` | `SERVER_BUSY` |

`429` 与 `503` 分开是有意的：前者是「你太快了」（退避重试能过），后者是「服务现在忙」（稍后重试或扩容）。

## 配置

优先级：**内置默认值 < 配置文件 < 环境变量 < 命令行参数**。

- 配置文件：`config/application.properties`（可用 `--config=<path>` 或环境变量 `PROBE_CONFIG` 改路径）
- 环境变量：把 key 转成大写、`.`/`-` 换成 `_` 并加前缀 `PROBE_`，例如 `probe.timeout-ms` → `PROBE_PROBE_TIMEOUT_MS`
- 命令行：`--probe.timeout-ms=1500`；布尔值可写 `--http.trust-proxy-header`（等价于 `=true`）

| key | 默认 | 范围 | 说明 |
| --- | --- | --- | --- |
| `server.bind` | `0.0.0.0` | — | 监听地址 |
| `server.port` | `8080` | 1–65535 | 监听端口 |
| `probe.timeout-ms` | `2000` | 50–60000 | 单次 TCP 连接超时 |
| `probe.dns-timeout-ms` | `2000` | 50–30000 | 单次 DNS 解析超时（见下） |
| `probe.max-in-flight` | `64` | 1–4096 | 同时进行的探测数（线程池大小，也是背压边界） |
| `probe.max-targets-per-request` | `200` | 1–5000 | 单请求目标数上限 |
| `task.max-concurrent` | `16` | 1–1024 | 同时执行的任务数，超出返回 503 |
| `task.workers` | `4` | 1–256 | 任务调度线程数 |
| `task.ttl-seconds` | `600` | 10–86400 | 已完成任务在内存里保留多久 |
| `rate-limit.global.rps` | `50` | ≥0.001 | 全局令牌生成速率 |
| `rate-limit.global.burst` | `100` | 1–1e6 | 全局桶容量 |
| `rate-limit.client.rps` | `10` | ≥0.001 | 每客户端令牌生成速率 |
| `rate-limit.client.burst` | `20` | 1–1e6 | 每客户端桶容量 |
| `sync.max-wait-ms` | `30000` | 100–300000 | `sync` 模式最多阻塞多久，超时转 202 |
| `http.max-body-bytes` | `1048576` | 1024–64MiB | 请求体上限，超出 413 |
| `http.trust-proxy-header` | `false` | — | 为 true 时用 `X-Forwarded-For` 第一段作为客户端标识；**只有前面确实有可信代理时才开**，否则限流可被伪造 |
| `log.level` | `INFO` | DEBUG/INFO/WARN/ERROR/OFF | 日志级别 |

未知 key 会直接启动失败并列出所有合法 key——配置写错不该等到某个请求才暴露。

## 设计要点

每一条的完整推理在 [docs/design.md](docs/design.md)，这里只给结论。

1. **零运行时依赖**：JDK 自带的 `HttpServer` + `SocketChannel` 足够表达「连得上/连不上/超时/解析失败」，还省掉了框架的隐式行为，面试时可以逐行解释。
2. **单一执行路径**：`sync` 不是另一套代码，只是调用方在同一个 HTTP 请求内等 `CompletableFuture` 完成。两条路径共用同一套并发控制与截止时间逻辑。
3. **批级截止时间**：`overallDeadlineMs = ceil(n / maxInFlight) × (dnsTimeout + connectTimeout + 500) + 2s`，上限 `sync.max-wait-ms + 5s`。到点还没开始的探测直接记 `TIMEOUT`（detail 为 `batch deadline exceeded …`），整批必然在有限时间内返回，结果顺序与输入一致。
4. **DNS 也要限时**：`InetAddress.getAllByName` 本身没有超时，一个挂起的解析会吃光整批预算，把「DNS 卡住」误报成「连接超时」。所以 DNS 跑在独立的小线程池里，用 `Future.get(timeout)` 掐断——加入这一层后，同一套测试从 11.8s 降到 0.8s，冒烟里 `no-such-host.invalid` 也从「批超时」变成明确的 `dns resolution timed out after 1200 ms`。
5. **两级令牌桶**：先扣全局桶，再扣客户端桶；全局拒绝时不动客户端桶（避免全局抖动把每个客户端的配额也吃掉）。`Retry-After` 由「还差多少令牌 / 生成速率」向上取整算出，不写死。
6. **背压而不是无限队列**：`probe.max-in-flight` 决定探测线程数，`task.max-concurrent` 用信号量挡在入口，两者共同保证内存和线程不会被流量打爆。
7. **可观测性**：每个响应都有 `X-Request-Id`，日志单行带级别与耗时；`/api/v1/stats` 覆盖 HTTP/任务/探测三层计数，能直接看出「是限流拒的、还是容量不够、还是目标本身不通」。

## 代码结构

```
src/main/java/dev/tcpprobe/
├── App.java                 启动、装配、优雅停机（signal hook）
├── config/                  Config(record) + ConfigLoader（三层优先级）
├── json/                    手写 JSON 解析/序列化（严格 RFC 8259）
├── probe/                   ProbeTarget / TcpProber / DnsResolver / ProbeResult
├── service/                 ProbeService / ProbeTask / TaskRegistry / RateLimiter / TokenBucket / Metrics
├── http/                    Router / Request / Response / Views / ProbeHttpServer
│   └── handler/             Submit / Task / Health / Stats / Root
├── error/                   ApiException（统一的 HTTP 错误）
└── util/                    Log

src/test/java/dev/tcpprobe/  75 个测试（单元 + 真实 socket 的端到端）
```

## 已知取舍与后续可以做的事

- 任务状态全部在内存里（重启即丢）。要做持久化的话，`TaskRegistry` 就是那个唯一需要换掉的边界。
- 限流是「客户端 IP + 进程内令牌桶」，多实例部署需要换成集中式配额（如 Redis 令牌桶）。
- 没有认证/TLS：题目没有要求，且把服务放在反代后面更合适；`http.trust-proxy-header` 默认关闭正是为此。
- 探测只做 TCP 握手，不做应用层协议；如果需要 HTTP/ICMP 探测，在 `TcpProber` 旁边加实现即可，`ProbeStatus` 的语义不用变。
- 没有压测结论：目前只在单机做过手工冒烟（同步/异步/限流/413/503 都验过），如果要上生产，下一步是补一个 `wrk`/JMH 基线。

## 关于 AI 使用

按题目允许的方式使用 AI 辅助（生成骨架、审查边界条件、补测试），但每一条设计决策本人都能解释「为什么这样做、备选方案是什么、代价在哪」，并记录在 [docs/design.md](docs/design.md)。
