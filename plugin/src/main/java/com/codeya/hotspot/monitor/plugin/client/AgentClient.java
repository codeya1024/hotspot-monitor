package com.codeya.hotspot.monitor.plugin.client;

import com.google.gson.Gson;
import com.codeya.hotspot.monitor.plugin.model.Snapshot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 与本地 agent 上报服务通信的客户端 */
public final class AgentClient {

    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public Snapshot fetchSnapshot(int port) throws Exception {
        String body = get("http://127.0.0.1:" + port + "/api/snapshot");
        return gson.fromJson(body, Snapshot.class);
    }

    public boolean health(int port) {
        try {
            String body = get("http://127.0.0.1:" + port + "/api/health");
            return body != null && body.contains("\"ok\":true");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 清空 agent 侧全部监控数据（重新记录） */
    public boolean clear(int port) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/clear"))
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Throwable t) {
            return false;
        }
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
