package com.codeya.hotspot.monitor.methodtest;

import com.codeya.hotspot.monitor.agent.Agent;
import com.codeya.hotspot.monitor.agent.core.RequestContext;
import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.model.MethodNode;
import com.codeya.hotspot.monitor.agent.model.RequestSpan;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方法级计时验证：hotspot.packages 包内方法插桩 → 请求内聚合自身/总耗时 + 时间线事件。
 * 独立包名是为了绕开 agent 自身排除规则（com.codeya.hotspot.monitor.agent 前缀不插桩）。
 */
class MethodTimingTest {

    @BeforeAll
    static void setup() throws Exception {
        Instrumentation inst = ByteBuddyAgent.install();
        Agent.premain("port=0,ring=200,slowSqlMs=10,slowHttpMs=10,packages=com.codeya.hotspot.monitor.methodtest", inst);
    }

    @BeforeEach
    void clean() {
        SpanStore.INSTANCE.clear();
    }

    @Test
    void recordsMethodSelfAndTotalTime() throws Exception {
        RequestContext.begin("GET", "/methodtest");
        try {
            SlowService.INSTANCE.doSlow(30);
        } finally {
            RequestSpan s = RequestContext.current();
            assertNotNull(s, "应有进行中的请求上下文");
            s.totalNanos = System.nanoTime() - s.startNanos;
            SpanStore.INSTANCE.recordRequest(s);
            RequestContext.end();
        }

        List<RequestSpan> reqs = SpanStore.INSTANCE.recentRequests();
        assertTrue(!reqs.isEmpty(), "应记录到请求");
        RequestSpan r = reqs.get(0);

        // 方法调用树：doSlow 应挂在根下、child 挂在 doSlow 下，自身耗时正确
        MethodNode slow = findChild(r.methodRoot, "doSlow");
        assertNotNull(slow, "根节点下应挂 doSlow，实际子节点: " + childNames(r.methodRoot));
        assertTrue(slow.totalNanos >= 30_000_000L,
                "doSlow 总耗时应 >= 30ms，实际 " + (slow.totalNanos / 1_000_000.0) + "ms");
        assertTrue(slow.selfNanos >= 25_000_000L,
                "doSlow 自身耗时（扣除子调用）应 >= 25ms，实际 " + (slow.selfNanos / 1_000_000.0) + "ms");
        MethodNode child = findChild(slow, "child");
        assertNotNull(child, "doSlow 下应挂子方法 child，实际: " + childNames(slow));
        assertTrue(child.selfNanos >= 4_000_000L,
                "child 自身耗时应 >= 4ms，实际 " + (child.selfNanos / 1_000_000.0) + "ms");
        assertTrue(slow.calls >= 1 && child.calls >= 1, "调用次数应 >=1");

        // 噪音修剪与业务 get 保留均在 doSlow 节点下验证（getConfig/setValue 是 doSlow 的子调用）
        MethodNode doSlowNode = findChild(r.methodRoot, "doSlow");
        assertNotNull(doSlowNode, "应找到 doSlow 节点");

        // 纯 setter 噪音（setValue，快速无子调用）应被修剪：doSlow 下不得出现
        assertTrue(findChild(doSlowNode, "setValue") == null,
                "setter 噪音应被修剪，实际子节点: " + childNames(doSlowNode));

        // 业务 get 方法（getConfig，慢且有子调用）不得被误杀
        MethodNode getConfig = findChild(doSlowNode, "getConfig");
        assertNotNull(getConfig, "业务 get 方法应保留，实际子节点: " + childNames(doSlowNode));
        assertTrue(getConfig.selfNanos >= 1_000_000L,
                "getConfig 自身耗时 >=1ms，实际 " + (getConfig.selfNanos / 1_000_000.0) + "ms");
    }

    private static MethodNode findChild(MethodNode parent, String suffix) {
        if (parent == null) {
            return null;
        }
        for (MethodNode c : parent.children.values()) {
            if (c.name.contains(suffix)) {
                return c;
            }
        }
        return null;
    }

    private static String childNames(MethodNode parent) {
        StringBuilder sb = new StringBuilder();
        for (MethodNode c : parent.children.values()) {
            sb.append(c.name).append(';');
        }
        return sb.toString();
    }

    @Test
    void callerChainComesFromInstrumentedStack() throws Exception {
        ChainProbe.captured = null;
        RequestContext.begin("GET", "/chain");
        try {
            ChainProbe.INSTANCE.run();
        } finally {
            RequestSpan s = RequestContext.current();
            assertNotNull(s, "应有进行中的请求上下文");
            s.totalNanos = System.nanoTime() - s.startNanos;
            SpanStore.INSTANCE.recordRequest(s);
            RequestContext.end();
        }

        assertNotNull(ChainProbe.captured, "插桩方法内应能捕获调用链");
        // 栈顶（离调用点最近）在前，简单类名.方法名
        assertTrue(ChainProbe.captured.startsWith("ChainProbe.deeper"),
                "栈顶应为最近进入的方法，实际: " + ChainProbe.captured);
        assertTrue(ChainProbe.captured.contains("ChainProbe.run"),
                "链中应含外层方法，实际: " + ChainProbe.captured);
        // 插桩栈天然无代理/框架噪音帧
        assertTrue(!ChainProbe.captured.contains("$Proxy")
                        && !ChainProbe.captured.contains("jdk.")
                        && !ChainProbe.captured.contains("com.codeya.hotspot.monitor.agent"),
                "链不应包含代理/JDK/agent 噪音帧，实际: " + ChainProbe.captured);
    }
}
