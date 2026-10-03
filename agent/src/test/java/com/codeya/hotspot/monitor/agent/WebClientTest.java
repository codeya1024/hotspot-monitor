package com.codeya.hotspot.monitor.agent;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.model.ChildSpan;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring WebClient（ExchangeFunction.exchange 异步 Mono）下游调用插桩验证 */
class WebClientTest {

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
            byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
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
    void recordsWebClientCall() throws Exception {
        String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/api/wc";
        String body = WebClient.create().get().uri(url).retrieve()
                .bodyToMono(String.class).block();
        assertTrue("ok".equals(body));

        ChildSpan span = TestUtil.waitForSpan("http", "GET " + url);
        assertNotNull(span, "应捕获到 WebClient 下游调用");
        assertTrue(span.nanos >= 0);
    }
}
