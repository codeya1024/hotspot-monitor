package com.codeya.hotspot.monitor.agent;

import com.codeya.hotspot.monitor.agent.core.MethodTracker;
import com.codeya.hotspot.monitor.agent.core.RequestContext;
import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.model.RequestSpan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方法树噪音过滤：单次调用最大耗时 < noiseThresholdMs（默认 5ms）且不含 SQL 的方法整棵子树忽略。
 * 含 SQL 的方法永不忽略（SQL 的调用来源必须可见）。
 */
class NoiseFilterTest {

    /** 在一个请求上下文里执行 body，返回该请求的 methodTree JSON */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> captureTree(Runnable body) {
        RequestSpan req = RequestContext.begin("GET", "/n");
        try {
            body.run();
        } finally {
            RequestContext.end();
        }
        SpanStore.INSTANCE.recordRequest(req);
        try {
            Map<String, Object> snap = SpanStore.INSTANCE.snapshot();
            List<Map<String, Object>> reqs = (List<Map<String, Object>>) snap.get("requests");
            return (Map<String, Object>) reqs.get(0).get("methodTree");
        } finally {
            SpanStore.INSTANCE.clear();
        }
    }

    private static boolean hasNode(Map<String, Object> tree, String nameSuffix) {
        if (String.valueOf(tree.get("name")).endsWith(nameSuffix)) {
            return true;
        }
        Object ch = tree.get("children");
        if (!(ch instanceof List)) {
            return false;
        }
        for (Object o : (List<?>) ch) {
            if (hasNode((Map<String, Object>) o, nameSuffix)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void fastNoSqlMethodIsCarriedForPluginToFold() {
        // v8.12：噪音折叠移到插件侧，agent 全量输出——快且无 SQL 的方法保留在树里，
        // 插件按 maxNanos < noiseThresholdMs && !hasSql 折叠为"已过滤高频调用"占位行
        Map<String, Object> tree = captureTree(() -> {
            int d = MethodTracker.enter("com.demo.FastNoise.noop");
            MethodTracker.exit(d);
        });
        assertTrue(hasNode(tree, "FastNoise.noop"), "agent 全量输出：噪音节点保留（由插件折叠）");
        Map<String, Object> node = findNode(tree, "FastNoise.noop");
        assertFalse(Boolean.TRUE.equals(node.get("hasSql")), "无 SQL 方法 hasSql=false");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findNode(Map<String, Object> tree, String nameSuffix) {
        if (String.valueOf(tree.get("name")).endsWith(nameSuffix)) {
            return tree;
        }
        Object ch = tree.get("children");
        if (!(ch instanceof List)) {
            return null;
        }
        for (Object o : (List<?>) ch) {
            Map<String, Object> hit = findNode((Map<String, Object>) o, nameSuffix);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @Test
    void slowMethodIsKept() throws InterruptedException {
        Map<String, Object> tree = captureTree(() -> {
            int d = MethodTracker.enter("com.demo.SlowService.query");
            try {
                Thread.sleep(15); // 单次最大 > 5ms 阈值
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            MethodTracker.exit(d);
        });
        assertTrue(hasNode(tree, "SlowService.query"), "单次调用 >= 5ms 的方法必须保留");
    }

    @Test
    void fastMethodWithSqlIsKept() {
        Map<String, Object> tree = captureTree(() -> {
            int d = MethodTracker.enter("com.demo.SqlHolder.loadConfig");
            MethodTracker.markHasSql(); // 模拟该方法内执行过 SQL
            MethodTracker.exit(d);
        });
        assertTrue(hasNode(tree, "SqlHolder.loadConfig"), "快但含 SQL 的方法必须保留（SQL 来源）");
    }

    @Test
    void sqlBearingSetterIsNotTrimmed() {
        Map<String, Object> tree = captureTree(() -> {
            // beanShape=true：setXxx 形态，但执行了 SQL → 不得被纯访问器修剪摘除
            int d = MethodTracker.enter("com.demo.Foo.setId", true);
            MethodTracker.markHasSql();
            MethodTracker.exit(d);
        });
        assertTrue(hasNode(tree, "Foo.setId"), "含 SQL 的 setXxx 方法不得被修剪");
    }

    @Test
    void sqlAttachesToBusinessMethodNotFramework() {
        Map<String, Object> tree = captureTree(() -> {
            // 模拟真实调用栈：框架拦截器 → 业务 Mapper（SQL 应挂到 Mapper，而不是拦截器）
            int fw = MethodTracker.enter("com.demo.interceptor.AppMybatisInterceptor.intercept");
            int biz = MethodTracker.enter("com.demo.mapper.UserMapper.find");
            MethodTracker.markHasSql();
            MethodTracker.attachSql(new com.codeya.hotspot.monitor.agent.model.ChildSpan("sql", "select * from t_user", 0L, 3_000_000L, true, "UserMapper.find"));
            MethodTracker.exit(biz);
            MethodTracker.exit(fw);
        });
        // SQL 挂在 UserMapper.find 节点下
        assertTrue(hasSqlUnder(tree, "UserMapper.find", "select * from t_user"), "SQL 应挂到业务 Mapper 方法");
        // 拦截器节点下不应有 SQL
        assertFalse(hasSqlUnder(tree, "AppMybatisInterceptor.intercept", "select * from t_user"), "框架拦截器不应挂 SQL");
    }

    @Test
    void rootNodeShowsRequestTotal() {
        RequestSpan req = RequestContext.begin("GET", "/n");
        try {
            int d = MethodTracker.enter("com.demo.SlowService.query");
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            MethodTracker.exit(d);
        } finally {
            RequestContext.end();
        }
        req.totalNanos = 20_000_000L; // 请求总耗时（真实场景由 ServletAdvice 记录）
        SpanStore.INSTANCE.recordRequest(req);
        try {
            Map<String, Object> snap = SpanStore.INSTANCE.snapshot();
            List<Map<String, Object>> reqs = (List<Map<String, Object>>) snap.get("requests");
            Map<String, Object> tree = (Map<String, Object>) reqs.get(0).get("methodTree");
            assertEquals(20_000_000L, tree.get("totalNanos"), "根节点应展示请求总耗时");
            assertEquals(1L, tree.get("calls"));
        } finally {
            SpanStore.INSTANCE.clear();
        }
    }

    private static boolean hasSqlUnder(Map<String, Object> tree, String methodSuffix, String sqlPrefix) {
        if (String.valueOf(tree.get("name")).endsWith(methodSuffix)) {
            Object sqls = tree.get("sqls");
            if (sqls instanceof List) {
                for (Object o : (List<?>) sqls) {
                    Map<String, Object> sm = (Map<String, Object>) o;
                    if (String.valueOf(sm.get("label")).startsWith(sqlPrefix)) {
                        return true;
                    }
                }
            }
            return false;
        }
        Object ch = tree.get("children");
        if (!(ch instanceof List)) {
            return false;
        }
        for (Object o : (List<?>) ch) {
            if (hasSqlUnder((Map<String, Object>) o, methodSuffix, sqlPrefix)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void thresholdZeroDisablesFilter() {
        Config cfg = Config.from(null);
        cfg.noiseThresholdMs = 0; // 关闭噪音过滤
        SpanStore.INSTANCE.configure(cfg);
        try {
            Map<String, Object> tree = captureTree(() -> {
                int d = MethodTracker.enter("com.demo.FastNoise.noop");
                MethodTracker.exit(d);
            });
            assertTrue(hasNode(tree, "FastNoise.noop"), "阈值 0 = 关闭过滤，快方法也保留");
        } finally {
            SpanStore.INSTANCE.configure(Config.from(null)); // 恢复默认
        }
    }
}
