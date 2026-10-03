package com.codeya.hotspot.monitor.agent;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.model.RequestSpan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** javax.servlet 栈（Boot 2 / 老项目）插桩验证 */
class JavaxServletTest {

    @BeforeAll
    static void setup() {
        AgentTestSupport.install();
    }

    @BeforeEach
    void clean() {
        SpanStore.INSTANCE.clear();
    }

    @Test
    void recordsRequestSpan() throws Exception {
        StubServlet servlet = new StubServlet();
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{HttpServletRequest.class}, new ReqHandler());
        HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{HttpServletResponse.class}, new RespHandler());

        servlet.service(req, resp);

        RequestSpan s = TestUtil.waitForRequest("/svc/javax");
        assertNotNull(s, "应捕获到请求 span");
        assertEquals("POST", s.method);
        assertEquals("/svc/javax", s.path);
        assertEquals(200, s.status);
        assertTrue(s.totalNanos >= 0);
    }

    public static class StubServlet extends javax.servlet.http.HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
            // 空实现即可，重点是入口被插桩
        }
    }

    static class ReqHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getMethod":
                    return "POST";
                case "getRequestURI":
                    return "/svc/javax";
                default:
                    return defaultValue(method.getReturnType());
            }
        }
    }

    static class RespHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("getStatus".equals(method.getName())) {
                return 200;
            }
            return defaultValue(method.getReturnType());
        }
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }
}
