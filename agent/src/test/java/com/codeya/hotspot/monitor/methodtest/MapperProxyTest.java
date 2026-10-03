package com.codeya.hotspot.monitor.methodtest;

import com.codeya.hotspot.monitor.agent.Agent;
import com.codeya.hotspot.monitor.agent.core.RequestContext;
import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.model.MethodNode;
import com.codeya.hotspot.monitor.agent.model.RequestSpan;
import com.codeya.hotspot.monitor.methodtest.mapper.FakeMapper;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.apache.ibatis.binding.MapperProxy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatis Mapper 代理插桩验证：Mapper 接口方法在 JDK 动态代理上执行，
 * 通过插桩 MapperProxy.invoke 恢复 "接口名.方法名" 这一层树节点（Pinpoint 同款）。
 */
class MapperProxyTest {

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
    void mapperInterfaceMethodShowsInMethodTree() {
        RequestContext.begin("GET", "/mapper");
        try {
            FakeMapper mapper = (FakeMapper) Proxy.newProxyInstance(
                    FakeMapper.class.getClassLoader(),
                    new Class[]{FakeMapper.class},
                    new MapperProxy<FakeMapper>(null, FakeMapper.class, new HashMap<>()));
            try {
                mapper.getPortalIds("u1");
            } catch (Throwable ignore) {
                // 无 SqlSession，invoke 内部必然失败；这里只验证 MapperProxy.invoke 插桩层被记录
            }
        } finally {
            RequestSpan s = RequestContext.current();
            if (s != null) {
                s.totalNanos = System.nanoTime() - s.startNanos;
                SpanStore.INSTANCE.recordRequest(s);
            }
            RequestContext.end();
            RequestSpan r = SpanStore.INSTANCE.recentRequests().get(0);
            MethodNode m = findChild(r.methodRoot, "FakeMapper.getPortalIds");
            assertNotNull(m, "方法树应含 Mapper 接口方法节点（接口名.方法名），实际子节点: " + childNames(r.methodRoot));
            assertTrue(m.calls >= 1, "Mapper 方法调用次数应 >=1，实际 " + m.calls);
        }
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
}
