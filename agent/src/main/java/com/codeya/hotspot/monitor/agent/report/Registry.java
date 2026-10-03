package com.codeya.hotspot.monitor.agent.report;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程注册表：把“pid -> 上报端口”写到临时目录，供 IDEA 插件自动发现。
 * 行式格式（避免 JSON 解析依赖）：
 *   hotspot-agent pid=1234 port=28765 app=demo startedAt=1690000000000 version=1.0.0
 */
public final class Registry {

    public static final String FILE =
            System.getProperty("java.io.tmpdir") + File.separator + "hotspot-agent-registry.txt";
    /** 与插件共用的固定位置：跨 JVM 的 tmpdir 可能不同，home 目录同一用户恒定 */
    public static final String HOME_FILE =
            System.getProperty("user.home") + File.separator + ".hotspot-agent-registry.txt";

    private Registry() {
    }

    public static void write(long pid, int port, String app, String version) {
        writeTo(FILE, pid, port, app, version);
        writeTo(HOME_FILE, pid, port, app, version);
    }

    private static void writeTo(String path, long pid, int port, String app, String version) {
        try {
            File f = new File(path);
            Map<String, String> entries = new LinkedHashMap<String, String>();
            if (f.exists()) {
                for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    if (!line.startsWith("hotspot-agent")) {
                        continue;
                    }
                    String p = extract(line, "pid=");
                    if (p != null && !p.isEmpty() && !p.equals(String.valueOf(pid))) {
                        entries.put(p, line);
                    }
                }
            }
            String line = "hotspot-agent pid=" + pid
                    + " port=" + port
                    + " app=" + app
                    + " startedAt=" + System.currentTimeMillis()
                    + " version=" + version;
            entries.put(String.valueOf(pid), line);

            // 只保留最新的 20 个 agent（删最旧，留最新）
            while (entries.size() > 20) {
                String firstKey = entries.keySet().iterator().next();
                entries.remove(firstKey);
            }

            StringBuilder sb = new StringBuilder();
            for (String l : entries.values()) {
                sb.append(l).append('\n');
            }
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable t) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ignore) {
            // 注册表写失败不影响监控本身
        }
    }

    private static String extract(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) {
            return null;
        }
        int start = i + key.length();
        int end = line.indexOf(' ', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }
}
