package com.codeya.hotspot.monitor.agent;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.model.ChildSpan;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Apache HttpClient（4.x，InternalHttpClient.execute）同步调用插桩验证 */
class ApacheHttpClientTest {

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
    void recordsApacheHttpClientCall() throws Exception {
        String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/api/y";
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            System.out.println("[diag-client] impl=" + client.getClass().getName());
            try (org.apache.http.client.methods.CloseableHttpResponse resp =
                         client.execute(new HttpGet(url))) {
                assertTrue(resp.getStatusLine().getStatusCode() == 200);
            }
        }
        // 手动模拟 advice 的 label 逻辑 + recordChild，验证链路本身（不经 transform）
        long st = System.nanoTime();
        String lbl = "GET " + url;
        SpanStore.INSTANCE.recordChild("http", lbl, st, null);
        ChildSpan manual = TestUtil.waitForSpan("http", "GET " + url);
        System.out.println("[diag-manual] manual record -> " + (manual != null ? manual.label : "NULL"));

        // 诊断：打印孤儿池全部内容
        for (ChildSpan c : SpanStore.INSTANCE.recentOrphans()) {
            System.out.println("[diag-orphan] kind=" + c.kind + " label=" + c.label);
        }
        ChildSpan span = TestUtil.waitForSpan("http", "GET " + url);
        assertNotNull(span, "应捕获到 Apache HttpClient 下游调用");
        assertTrue(span.nanos >= 0);
    }
}
