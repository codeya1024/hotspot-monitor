package com.codeya.hotspot.monitor.agent.report;

import com.codeya.hotspot.monitor.agent.AgentRuntime;
import com.codeya.hotspot.monitor.agent.Config;
import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 本地上报服务：只监听 127.0.0.1，供 IDEA 插件拉取监控快照。
 *   GET  /api/snapshot  完整快照（请求 + 端点 + 慢SQL + 慢HTTP + 孤立片段）
 *   GET  /api/health    健康检查
 *   POST /api/clear     清空全部监控数据（重新记录）
 */
public final class Reporter {

    private final Config cfg;
    private HttpServer server;
    private int port = -1;

    public Reporter(Config cfg) {
        this.cfg = cfg;
    }

    public void start() throws IOException {
        if (cfg.port == 0) {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } else {
            int attempt = 0;
            while (true) {
                try {
                    server = HttpServer.create(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), cfg.port + attempt), 0);
                    break;
                } catch (BindException e) {
                    attempt++;
                    if (attempt > 100) {
                        throw e;
                    }
                }
            }
        }
        port = server.getAddress().getPort();

        server.createContext("/api/snapshot", this::handleSnapshot);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/clear", this::handleClear);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "hotspot-reporter");
            t.setDaemon(true);
            return t;
        }));
        server.start();

        try {
            Registry.write(Long.parseLong(AgentRuntime.pid()), port, AgentRuntime.appName(), "1.0.0");
        } catch (Throwable ignore) {
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public int port() {
        return port;
    }

    private void handleSnapshot(HttpExchange ex) throws IOException {
        byte[] body = Json.write(SpanStore.INSTANCE.snapshot()).getBytes(StandardCharsets.UTF_8);
        respond(ex, 200, "application/json", body);
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("pid", AgentRuntime.pid());
        m.put("app", AgentRuntime.appName());
        m.put("port", port);
        respond(ex, 200, "application/json", Json.write(m).getBytes(StandardCharsets.UTF_8));
    }

    /** 清空全部监控数据（请求 / 端点 / 慢SQL / 慢HTTP / 孤立片段），重新开始记录 */
    private void handleClear(HttpExchange ex) throws IOException {
        SpanStore.INSTANCE.clear();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("cleared", true);
        respond(ex, 200, "application/json", Json.write(m).getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        try {
            ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(code, body.length);
            if (body.length > 0) {
                ex.getResponseBody().write(body);
            }
        } finally {
            ex.close();
        }
    }
}
