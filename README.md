# Hotspot Monitor — HTTP Request Profiler for Java (XRebel-like)

A lightweight **Java Agent + IntelliJ IDEA plugin** that profiles the latency distribution of every HTTP request in your Java web service with zero code changes — pinpoint exactly where the time goes: **slow SQL / downstream HTTP calls / overall request breakdown**.

Works on **JDK 8 / 21 / 25**, supports both **javax.servlet (Boot 2 / legacy)** and **jakarta.servlet (Boot 3 / modern)** stacks. Built entirely with **Maven**.

```
┌────────────────────────────┐      ┌──────────────────────────────┐
│  Monitored service (-javaagent)│     │  IntelliJ IDEA 2026.2 plugin  │
│  ┌────────────────────────┐ │      │  ┌────────────────────────┐  │
│  │ Servlet entry advice   │ │      │  │ Request list + waterfall │  │
│  │ JDBC advice (SQL time) │ │      │  │ Slow SQL / slow HTTP     │  │
│  │ RestTemplate/OkHttp    │ │      │  │ Endpoint stats (P50/P95) │  │
│  └───────────┬────────────┘ │      │  └───────────▲────────────┘  │
│              │ ring buffer+agg│      │              │ polling       │
│   127.0.0.1:28765 HTTP report─┼──────┼──▶ /api/snapshot            │
│   process registry (pid→port)─┼──────┼──▶ auto-discovery          │
└────────────────────────────┘      └──────────────────────────────┘
```

## Module layout

| Module | Description |
|---|---|
| `agent` | Java Agent: Byte Buddy bytecode instrumentation + data aggregation + local HTTP report server (Java 8 bytecode) |
| `plugin` | IntelliJ Platform plugin (since-build 262 / IDEA 2026.2): tool window UI |
| `demo`  | Spring Boot 3 demo service for end-to-end validation |

## Quick start

### 1. Build (Maven)

```
# Agent fat-jar (bundles Byte Buddy; manifest has Premain-Class; Java 8 bytecode → JDK 8/21/25)
mvn -pl agent package

# IDEA plugin zip
# Note: the plugin module is compiled against your local IDEA libs (plugin/pom.xml idea.lib.dir,
#       default /Applications/IntelliJ IDEA 2.app/Contents/lib = 2026.2),
#       IDEA 2026.2 platform is Java 25 bytecode → build requires JDK ≥ 25:
JAVA_HOME=/path/to/jdk-25 mvn -pl plugin package
```

Artifacts:

- `agent/target/hotspot-agent.jar`
- `plugin/target/hotspot-monitor.zip` (contains `hotspot-monitor/lib/hotspot-monitor.jar`)

### 2. Attach the agent to your service (no code changes)

```
java -javaagent:/path/to/hotspot-agent.jar -jar your-service.jar
```

On startup the console prints:

```
[hotspot-agent] premain OK (...) reportPort=28765 slowSqlMs=200ms slowHttpMs=300ms
```

### 3. Open the plugin and inspect requests

IDEA → `Preferences → Plugins → ⚙️ → Install Plugin from Disk...` → pick `plugin/target/hotspot-monitor.zip`, restart, and the **Hotspot Monitor** tool window appears on the right:

- **Agent dropdown**: auto-discovers local processes running with the agent (from the registry); pick one and click **Connect**. You can also enter a port manually.
- **Request list**: live requests + status + total time + SQL/HTTP breakdown
- **Waterfall**: select a request to see method (green) / SQL (orange) / downstream HTTP (blue) / exception (red) spans; the panel itself is copyable (drag-select + Ctrl+C, or right-click → copy / copy all); every row shows the offset from request start (`[+12.3ms]`) and the business caller of each SQL/HTTP (`← DemoApplication.sql(80)`)
- **Methods**: aggregated method timing for the selected request (self ms / total ms / count / max ms, sorted by self time)
- **Slow SQL / Slow HTTP**: aggregated calls above threshold (count / total time / max time / recent caller chain)
- **Endpoint stats**: per-endpoint count / avg / P50 / P95 / max
- **Orphan SQL/HTTP**: SQL/HTTP with no request context (background tasks, thread pools, async callbacks)
- **Table copy / sort**: every table supports `Ctrl+C` / right-click copy (copies the rendered cell text); time columns sort numerically, timestamp columns by time
- **Clear**: toolbar **Clear** button resets the connected agent's data (`POST /api/clear`)

## Agent configuration (`-D` system property or `-javaagent` arg `k=v`)

| Property | Default | Description |
|---|---|---|
| `hotspot.port` | `28765` | Local report port; `0` = random; auto-avoids busy ports (+1) |
| `hotspot.ring` | `500` | Request ring-buffer size |
| `hotspot.slowSqlMs` | `200` | Slow SQL threshold (ms) |
| `hotspot.slowHttpMs` | `300` | Slow downstream HTTP threshold (ms) |
| `hotspot.packages` | derived from main class | Method-level timing class prefixes (comma-separated); empty = timing off |

Example:

```
java -javaagent:hotspot-agent.jar="slowSqlMs=100,slowHttpMs=200" -Dhotspot.port=29000 -jar app.jar
```

> Method timing by default covers the main class's package (e.g. `com.xxx.app`);
> narrow it with `-Dhotspot.packages=com.xxx.service,com.xxx.mapper`.
> Set it to empty (`-Dhotspot.packages=`) to disable method timing entirely
> (full instrumentation has a small runtime cost; intended for dev/debug sessions only).

## Instrumentation points

| Target | Description |
|---|---|
| `javax.servlet.http.HttpServlet.service` | Boot 2 / legacy request entry |
| `jakarta.servlet.http.HttpServlet.service` | Boot 3 request entry |
| `java.sql.Connection/Statement` implementations | `prepareStatement` / `execute*` (prepared statements also captured) |
| `org.springframework.web.client.RestTemplate.doExecute` | Spring downstream HTTP (both 4-arg and 5-arg overloads; Spring 6.1+ actually uses 5-arg) |
| `okhttp3.internal.connection.RealCall.execute` | OkHttp 4.x sync calls (`okhttp3.RealCall` doesn't exist in 3.x) |
| `org.apache.http.impl.client.CloseableHttpClient.execute` (4.x) / `org.apache.hc.client5.impl.classic.InternalHttpClient.execute` (5.x) | Apache HttpClient downstream (2-arg overload) |
| `org.springframework.web.reactive.function.client.ExchangeFunctions$DefaultExchangeFunction.exchange` | Spring WebClient downstream (async Mono completion callback) |
| all methods inside `hotspot.packages` | **Method-level timing** (non-constructor / non-abstract): per-request aggregation of self time / total time / count / max time, plus a method timeline |

> Pool proxies (Hikari / Druid / DBCP / Tomcat / c3p0 / UCP) are excluded — only the underlying JDBC driver is instrumented, so the same SQL is never counted twice.
>
> **Every SQL / downstream HTTP captures its business caller**: the caller comes from the instrumented method execution stack (XRebel-style: the request context maintains a call stack, and when SQL/HTTP happens the stack top is "the method executing it"). This naturally contains only business methods under `hotspot.packages` — **no `$Proxy` / MyBatis interceptor / Druid filter noise frames**. Without `hotspot.packages` it falls back to a filtered thread-dump snapshot (which may include framework frames). Visible in the waterfall rows and the Slow SQL tab's "caller chain" column.

## Known limitations

- **Cross-thread async calls** (SQL/HTTP/methods in thread pools, `CompletableFuture`) are not attached to the original request; they land in the **Orphan SQL/HTTP** tab. Method timing likewise only counts calls on the request thread.
- **JDK built-in `java.net.http.HttpClient`** is not instrumented yet (RestTemplate / OkHttp / Apache HttpClient / WebClient cover the mainstream paths).
- **OkHttp async `enqueue`** is not instrumented — sync `execute` only.
- **Method timing only covers classes under `hotspot.packages`**: framework / container / dependency classes are excluded (to avoid performance explosion). If a method is slow but its SQL is fast, add the business package to `hotspot.packages`.
- The method-timing advice does not use `skipOn` optimization (verified that combined with object return values it changes target method behavior — see the dev notes below); it uses a single null-check in exit instead, negligible overhead.

## Verification

```
# 1. Agent instrumentation tests (real attach to a test JVM: both servlet stacks / H2 JDBC /
#    RestTemplate / OkHttp / JSON / method timing)
mvn -pl agent test   # all green

# 2. End-to-end demo (port 18080, agent attached automatically; exec:java can't attach an
#    agent, so use exec:exec)
mvn -pl demo exec:exec
```

Then (in another terminal):

```
curl -s localhost:18080/hello
curl -s localhost:18080/sql        # slow SQL (GROUP BY over 1M rows, ~300ms+, over default threshold)
curl -s localhost:18080/slow       # business bottleneck (sleep 400ms, self time visible)
curl -s localhost:18080/downstream # downstream RestTemplate + OkHttp → /slow (~450ms, over slow-HTTP threshold)
curl -s localhost:18080/error
curl -s localhost:28765/api/snapshot | python3 -m json.tool
```

The snapshot should contain: the request list (with SQL/HTTP child spans **and business caller chains**), method stats, method timeline events, endpoint stats (count/avg/P50/P95/max), `slowSqls` (slow SQL aggregation with `lastCaller`), and `slowHttps`.

## Dual servlet stack (javax + jakarta)

- Agent main code is compiled to **Java 8 bytecode** (`maven-compiler-plugin` `release=8`), so it runs on any monitored JDK (8/21/25).
- Two servlet advices match `javax.servlet.http.HttpServlet` and `jakarta.servlet.http.HttpServlet` respectively; only classes of the stack present in the service get instrumented.
- `servlet-api` / `okhttp` are `provided` dependencies: compile/test only, **not packaged** into the agent jar — provided at runtime by the monitored application's own classloader, never polluting its classpath.

## Development notes (pitfalls encountered)

- **Advice member visibility**: when Byte Buddy inlines advice bodies into target classes, any helper called from the inlined code (e.g. `truncate`) must be `public`, otherwise you get a runtime `IllegalAccessError` that is swallowed by the advice's try/catch (looks like "instrumentation silently fails").
- **OkHttp `@Advice.This`**: don't write a typed `@Advice.This RealCall` — during transformation it triggers nested loading of the class being defined (`LinkageError: duplicate class definition`); use `@Advice.This Object` and cast inside the body.
- **Spring 6.1+ RestTemplate**: `doExecute` has 4-arg and 5-arg overloads; the public 4-arg `execute` forwards to the 5-arg one, so both advices must be registered.
- **Plugin compilation**: IDEA 2026.2 platform jars are Java 25 bytecode and highly modular (437 lib jars); javac's `dir/*` wildcard doesn't expand on the composite classpath maven-compiler-plugin builds, so the plugin module uses `maven-antrun-plugin` to expand the lib dir explicitly before running javac (build requires JDK ≥ 25). 2026.2 removed `com.intellij.ui.components.JBTable`; the UI uses plain Swing components.
- **Method advice `skipOn` is disabled**: `@Advice.OnMethodEnter(skipOn=OnNonDefaultValue)` combined with an object return value **skips the whole target method body** (methods appear ~µs, `sleep` stops working); the correct approach is to return the object from enter and null-check in exit (one check, negligible).
- **Method-timing package**: only list business package prefixes in `-Dhotspot.packages`; the agent's own package `com.codeya.hotspot.monitor.agent` plus `java.*`/`jdk.*` are always excluded to avoid recursive instrumentation.

## License / known limitations

- **License**: Apache License 2.0 (see root LICENSE). Free to use, modify, distribute — retain the copyright notice; no warranty.
- **Known limitations / roadmap** (PRs welcome):
  - Snapshot is fully serialized (request → full method tree + spans + aggregates); very large requests (hundreds of thousands of method nodes) stress the report payload and plugin rendering — truncation/sampling planned.
  - Slow SQL / slow HTTP aggregation keys on content and caps output at 100 entries; an LRU is worth considering for long-running high-cardinality services.
  - Method timing only covers app packages derived from `hotspot.packages` (framework packages excluded by default).
  - Async threads (e.g. WebClient callbacks) are not linked to the request context; their spans land in Orphan SQL/HTTP — a deliberate design trade-off.
  - The local report server listens on 127.0.0.1 with no auth: any other process on the same machine can read the monitoring data — don't run it on shared hosts with sensitive data.

## Changelog

- Cell selection & copy: single-click selects one cell in the five detail tables (⌘C copies it); double-click copies a cell anywhere; right-click menu: copy cell / selection / row / whole table (TSV with header).
- New downstream HTTP instrumentation: Apache HttpClient (4.x/5.x) and Spring WebClient (async Mono callback → orphan span when no request context).
- Agent dropdown ↔ port field sync; plugin version 1.0 (Marketplace-ready).
- All tables/tree use monospaced font for column alignment.
- Byte Buddy relocated to `com.codeya.hotspot.monitor.agent.shaded.bytebuddy`: zero classpath conflict with applications that bundle their own Byte Buddy.
- Orphan SQL/HTTP tab with caller + occurrence-time columns.
- Waterfall layout: timeline/tree get independent scrollbars (splitter default 30%); high-frequency small calls folded into expandable placeholders; "unattributable self time" hints; per-call average.
- Method names include parameter signatures (overload-safe); `MapperProxy.invoke` renders signatures at runtime.
- Framework packages excluded by default; call-stack cleanup on request end; multi-package agent args; incremental request-table refresh; zero-allocation method stack; duplicate method rows merged by fully-qualified name.
- SQL attached to the method tree; waterfall timeline as a copyable table; method-tree noise filtering (below `hotspot.noiseThresholdMs` default 5ms, no SQL → subtree dropped; methods with SQL are never dropped).

Full Chinese changelog: see [README.zh-CN.md](README.zh-CN.md).

---

**中文版文档：[README.zh-CN.md](README.zh-CN.md)**
