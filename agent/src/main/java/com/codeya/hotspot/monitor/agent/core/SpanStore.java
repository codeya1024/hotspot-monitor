package com.codeya.hotspot.monitor.agent.core;

import com.codeya.hotspot.monitor.agent.AgentRuntime;
import com.codeya.hotspot.monitor.agent.Config;
import com.codeya.hotspot.monitor.agent.model.ChildSpan;
import com.codeya.hotspot.monitor.agent.model.MethodNode;
import com.codeya.hotspot.monitor.agent.model.RequestSpan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局监控数据仓库：
 * - 请求环形缓冲（最近 N 条）
 * - 端点聚合统计（count / avg / p50 / p95 / max）
 * - 慢 SQL / 慢下游 HTTP 聚合
 * - 孤立片段（无请求上下文时的 SQL/HTTP，如后台任务）
 */
public final class SpanStore {

    public static final SpanStore INSTANCE = new SpanStore();

    private final AtomicLong idSeq = new AtomicLong(1);
    private volatile Config cfg = Config.from(null);

    private final ArrayDeque<RequestSpan> requests = new ArrayDeque<>();
    private final ConcurrentHashMap<String, EndpointStats> endpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AggStats> slowSqls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AggStats> slowHttps = new ConcurrentHashMap<>();
    private final ArrayDeque<ChildSpan> orphanSpans = new ArrayDeque<>();

    private SpanStore() {
    }

    public void configure(Config cfg) {
        if (cfg != null) {
            this.cfg = cfg;
        }
    }

    public long nextId() {
        return idSeq.getAndIncrement();
    }

    // ---------- 写入 ----------

    public void recordRequest(RequestSpan r) {
        synchronized (requests) {
            requests.addFirst(r);
            while (requests.size() > cfg.ring) {
                requests.removeLast();
            }
        }
        // 端点聚合
        EndpointStats es = endpoints.computeIfAbsent(r.method + " " + r.path, k -> new EndpointStats());
        es.add(r.totalNanos, r.startAtMs);
    }

    /**
     * 记录一个子片段（SQL / 下游 HTTP）。有请求上下文就挂到请求上，否则进孤立列表。
     */
    public void recordChild(String kind, String label, long startNanos, Throwable thrown) {
        try {
            long nanos = System.nanoTime() - startNanos;
            boolean ok = thrown == null;
            // 优先取插桩执行栈（MethodTracker，XRebel 同款：只有业务方法，无代理/框架噪音）；
            // 栈为空（未配 packages / 异步线程）时兜底用线程栈快照过滤
            String caller = MethodTracker.currentChain(5);
            if (caller == null) {
                caller = CallStack.callerOf();
            }
            ChildSpan c = new ChildSpan(kind, label, startNanos, nanos, ok, caller);

            RequestSpan req = RequestContext.current();
            if (req != null) {
                req.addSpan(c);
            } else {
                synchronized (orphanSpans) {
                    orphanSpans.addFirst(c);
                    while (orphanSpans.size() > 200) {
                        orphanSpans.removeLast();
                    }
                }
            }

            if ("sql".equals(kind)) {
                // 标记当前方法调用栈"含 SQL"：这些方法不参与噪音过滤（SQL 调用来源必须可见）
                MethodTracker.markHasSql();
                // 挂到业务方法节点：方法树展开时直接看到该方法内执行的 SQL
                MethodTracker.attachSql(c);
                if (nanos >= cfg.slowSqlMs * 1_000_000L) {
                    AggStats a = slowSqls.computeIfAbsent(label, k -> new AggStats());
                    a.add(nanos, System.currentTimeMillis(), caller);
                }
            } else if ("http".equals(kind)) {
                if (nanos >= cfg.slowHttpMs * 1_000_000L) {
                    AggStats a = slowHttps.computeIfAbsent(label, k -> new AggStats());
                    a.add(nanos, System.currentTimeMillis(), caller);
                }
            }
        } catch (Throwable ignore) {
            // 监控代码绝不允许影响业务
        }
    }

    // ---------- 读取 ----------

    /** 清空全部监控数据（测试用） */
    public void clear() {
        synchronized (requests) {
            requests.clear();
        }
        synchronized (orphanSpans) {
            orphanSpans.clear();
        }
        endpoints.clear();
        slowSqls.clear();
        slowHttps.clear();
    }

    public List<RequestSpan> recentRequests() {
        synchronized (requests) {
            return new ArrayList<RequestSpan>(requests);
        }
    }

    public List<ChildSpan> recentOrphans() {
        synchronized (orphanSpans) {
            return new ArrayList<ChildSpan>(orphanSpans);
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();

        Map<String, Object> agent = new LinkedHashMap<String, Object>();
        agent.put("pid", AgentRuntime.pid());
        agent.put("app", AgentRuntime.appName());
        agent.put("version", "1.0.0");
        agent.put("startedAt", System.currentTimeMillis());
        root.put("agent", agent);

        Map<String, Object> thr = new LinkedHashMap<String, Object>();
        thr.put("slowSqlMs", cfg.slowSqlMs);
        thr.put("slowHttpMs", cfg.slowHttpMs);
        thr.put("noiseThresholdMs", cfg.noiseThresholdMs);
        root.put("thresholds", thr);

        List<Map<String, Object>> reqs = new ArrayList<Map<String, Object>>();
        for (RequestSpan r : recentRequests()) {
            reqs.add(requestJson(r));
        }
        root.put("requests", reqs);

        List<Map<String, Object>> eps = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, EndpointStats> e : endpoints.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            EndpointStats s = e.getValue();
            m.put("name", e.getKey());
            m.put("count", s.count());
            m.put("avgNanos", s.count() == 0 ? 0 : s.totalNanos() / s.count());
            m.put("p50Nanos", s.percentile(50));
            m.put("p95Nanos", s.percentile(95));
            m.put("maxNanos", s.maxNanos());
            m.put("lastAtMs", s.lastAtMs());
            eps.add(m);
        }
        Collections.sort(eps, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return ((Long) b.get("count")).compareTo((Long) a.get("count"));
            }
        });
        root.put("endpoints", eps);

        root.put("slowSqls", aggJson(slowSqls, 100));
        root.put("slowHttps", aggJson(slowHttps, 100));

        List<Map<String, Object>> orphans = new ArrayList<Map<String, Object>>();
        for (ChildSpan c : recentOrphans()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("kind", c.kind);
            m.put("label", c.label);
            m.put("nanos", c.nanos);
            m.put("ok", c.ok);
            m.put("caller", c.caller == null ? "" : c.caller);
            m.put("atMs", c.occurredAtMs);
            orphans.add(m);
        }
        root.put("orphanSpans", orphans);

        return root;
    }

    private List<Map<String, Object>> aggJson(ConcurrentHashMap<String, AggStats> map, int topN) {
        List<Map.Entry<String, AggStats>> list = new ArrayList<Map.Entry<String, AggStats>>(map.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, AggStats>>() {
            @Override
            public int compare(Map.Entry<String, AggStats> a, Map.Entry<String, AggStats> b) {
                return Long.compare(b.getValue().maxNanos(), a.getValue().maxNanos());
            }
        });
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < list.size() && i < topN; i++) {
            Map.Entry<String, AggStats> e = list.get(i);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            AggStats s = e.getValue();
            m.put("label", e.getKey());
            m.put("lastCaller", s.lastCaller());
            m.put("count", s.count());
            m.put("totalNanos", s.totalNanos());
            m.put("maxNanos", s.maxNanos());
            m.put("lastAtMs", s.lastAtMs());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> requestJson(RequestSpan r) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", r.id);
        m.put("startAtMs", r.startAtMs);
        m.put("method", r.method);
        m.put("path", r.path);
        m.put("status", r.status);
        m.put("thread", r.thread);
        m.put("error", r.error);
        m.put("totalNanos", r.totalNanos);
        m.put("sqlCount", r.sqlCount());
        m.put("sqlNanos", r.sqlNanos());
        m.put("httpCount", r.httpCount());
        m.put("httpNanos", r.httpNanos());

        List<Map<String, Object>> spans = new ArrayList<Map<String, Object>>();
        for (ChildSpan c : r.spans()) {
            Map<String, Object> sm = new LinkedHashMap<String, Object>();
            sm.put("kind", c.kind);
            sm.put("label", c.label);
            sm.put("nanos", c.nanos);
            sm.put("offsetNanos", c.startNanos - r.startNanos);
            sm.put("ok", c.ok);
            sm.put("caller", c.caller);
            spans.add(sm);
        }
        m.put("spans", spans);

        // 方法耗时按调用关系输出为方法调用树（methodTree）：方法 → 下级方法 → 每层耗时。
        Map<String, Object> tree = methodTreeToMap(r.methodRoot);
        // 根节点是请求入口（自身未 enter/exit，calls/total 恒 0）→ 兜底展示请求总耗时
        if (r.methodRoot.calls == 0) {
            tree.put("calls", 1L);
            tree.put("totalNanos", r.totalNanos);
            tree.put("maxNanos", r.totalNanos);
        }
        m.put("methodTree", tree);
        return m;
    }

    /**
     * 方法调用树 → JSON（子节点按总耗时倒序，最慢分支在前）。
     * 噪音折叠不再由 agent 做：v8.12 起全量输出（含单次 < 阈值且不含 SQL 的高频小调用，
     * 如 resultSet_next ×13073），由插件按 thresholds.noiseThresholdMs 折叠为可展开的
     * "已过滤 N 个高频子调用"占位行——高频调用的合计耗时不再静默丢失。
     */
    private static Map<String, Object> methodTreeToMap(MethodNode n) {
        Map<String, Object> mm = new LinkedHashMap<String, Object>();
        mm.put("name", n.name);
        mm.put("calls", n.calls);
        mm.put("selfNanos", n.selfNanos);
        mm.put("totalNanos", n.totalNanos);
        mm.put("maxNanos", n.maxNanos);
        mm.put("hasSql", n.hasSql);
        List<MethodNode> list = new ArrayList<MethodNode>(n.children.values());
        Collections.sort(list, new Comparator<MethodNode>() {
            @Override
            public int compare(MethodNode a, MethodNode b) {
                return Long.compare(b.totalNanos, a.totalNanos);
            }
        });
        List<Map<String, Object>> ch = new ArrayList<Map<String, Object>>();
        for (MethodNode c : list) {
            ch.add(methodTreeToMap(c));
        }
        mm.put("children", ch);
        List<Map<String, Object>> sqls = new ArrayList<Map<String, Object>>();
        for (ChildSpan s : n.childSqls) {
            Map<String, Object> sm = new LinkedHashMap<String, Object>();
            sm.put("label", s.label);
            sm.put("nanos", s.nanos);
            sm.put("ok", s.ok);
            sqls.add(sm);
        }
        mm.put("sqls", sqls);
        return mm;
    }

    /** 端点统计：保留最近 2000 次耗时用于分位数计算 */
    static final class EndpointStats {
        private final ArrayDeque<Long> durations = new ArrayDeque<Long>();
        private long count;
        private long totalNanos;
        private long maxNanos;
        private long lastAtMs;

        synchronized void add(long nanos, long atMs) {
            count++;
            totalNanos += nanos;
            if (nanos > maxNanos) {
                maxNanos = nanos;
            }
            lastAtMs = atMs;
            durations.addLast(nanos);
            while (durations.size() > 2000) {
                durations.removeFirst();
            }
        }

        synchronized long count() {
            return count;
        }

        synchronized long totalNanos() {
            return totalNanos;
        }

        synchronized long maxNanos() {
            return maxNanos;
        }

        synchronized long lastAtMs() {
            return lastAtMs;
        }

        synchronized long percentile(double p) {
            List<Long> sorted = new ArrayList<Long>(durations);
            if (sorted.isEmpty()) {
                return 0;
            }
            Collections.sort(sorted);
            int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            if (idx < 0) {
                idx = 0;
            }
            if (idx >= sorted.size()) {
                idx = sorted.size() - 1;
            }
            return sorted.get(idx);
        }
    }

    /** 慢 SQL / 慢 HTTP 聚合统计 */
    static final class AggStats {
        private long count;
        private long totalNanos;
        private long maxNanos;
        private long lastAtMs;
        private volatile String lastCaller;

        synchronized void add(long nanos, long atMs, String caller) {
            count++;
            totalNanos += nanos;
            if (nanos > maxNanos) {
                maxNanos = nanos;
            }
            lastAtMs = atMs;
            if (caller != null) {
                lastCaller = caller;
            }
        }

        synchronized long count() {
            return count;
        }

        synchronized long totalNanos() {
            return totalNanos;
        }

        synchronized long maxNanos() {
            return maxNanos;
        }

        synchronized long lastAtMs() {
            return lastAtMs;
        }

        synchronized String lastCaller() {
            return lastCaller;
        }
    }
}
