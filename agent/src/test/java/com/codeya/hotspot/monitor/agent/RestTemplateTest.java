package com.codeya.hotspot.monitor.agent;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.model.ChildSpan;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring RestTemplate 插桩验证（覆盖“下游 HTTP 耗时”） */
class RestTemplateTest {

    private HttpServer srv;

    @BeforeAll
    static void setup() {
        AgentTestSupport.install();
    }

    @BeforeEach
    void clean() throws Exception {
        SpanStore.INSTANCE.clear();
        srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        srv.createContext("/api", ex -> {
            byte[] b = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        srv.start();
    }

    @AfterEach
    void tearDown() {
        srv.stop(0);
    }

    @Test
    void recordsRestTemplateCall() throws InterruptedException {
        String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/api/x";
        new RestTemplate().getForObject(url, String.class);

        ChildSpan span = TestUtil.waitForSpan("http", "GET " + url);
        assertNotNull(span, "应捕获到 RestTemplate 下游调用");
        assertTrue(span.nanos >= 0);
        assertTrue(span.ok);
    }
}
