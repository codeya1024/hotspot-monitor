package com.codeya.hotspot.monitor.plugin.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取 agent 写在临时目录的注册表，自动发现本机被监控的服务实例。
 * 行格式：hotspot-agent pid=1234 port=28765 app=demo startedAt=... version=1.0.0
 *
 * 注意：插件运行在 IDEA 自己的 JVM 里，其 java.io.tmpdir 可能与
 * 被监控服务 JVM 的不一致（尤其 macOS 下），因此同时扫描：
 *  1. ~/.hotspot-agent-registry.txt        —— agent 新版固定写入位置（同一用户恒定）
 *  2. 本 JVM 的 java.io.tmpdir             —— 兼容旧版 agent
 *  3. macOS /var/folders 下各用户的 T 临时目录 —— 覆盖 IDEA 与服务 tmpdir 不一致的场景
 * 同一 pid+port 只保留一条。
 */
public final class RegistryReader {

    public static final String FILE =
            System.getProperty("java.io.tmpdir") + File.separator + "hotspot-agent-registry.txt";
    public static final String HOME_FILE =
            System.getProperty("user.home") + File.separator + ".hotspot-agent-registry.txt";

    public static final class AgentEntry {
        public final String pid;
        public final int port;
        public final String app;

        public AgentEntry(String pid, int port, String app) {
            this.pid = pid;
            this.port = port;
            this.app = app;
        }

        @Override
        public String toString() {
            return (app == null || app.isEmpty() ? "pid=" + pid : app) + "  (端口 " + port + ")";
        }
    }

    private RegistryReader() {
    }

    public static List<AgentEntry> list() {
        Map<String, AgentEntry> byKey = new LinkedHashMap<String, AgentEntry>();
        for (String path : candidates()) {
            File f = new File(path);
            if (!f.exists()) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (!line.startsWith("hotspot-agent")) {
                        continue;
                    }
                    String pid = extract(line, "pid=");
                    String port = extract(line, "port=");
                    String app = extract(line, "app=");
                    if (pid == null || port == null) {
                        continue;
                    }
                    if (!isAlive(pid)) {
                        continue; // 跳过已退出的进程（注册表残留项）
                    }
                    try {
                        int p = Integer.parseInt(port.trim());
                        byKey.putIfAbsent(pid + ":" + p, new AgentEntry(pid, p, app));
                    } catch (NumberFormatException ignore) {
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        return new ArrayList<AgentEntry>(byKey.values());
    }

    private static boolean isAlive(String pid) {
        try {
            return ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false);
        } catch (Throwable t) {
            return true; // 无法判断时保守保留，避免误杀
        }
    }

    private static List<String> candidates() {
        List<String> paths = new ArrayList<String>();
        paths.add(HOME_FILE);
        paths.add(FILE);
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            try {
                File var = new File("/var/folders");
                File[] users = var.listFiles();
                if (users != null) {
                    for (File u : users) {
                        File[] inner = u.listFiles();
                        if (inner == null) {
                            continue;
                        }
                        for (File i : inner) {
                            if ("T".equals(i.getName())) {
                                paths.add(new File(i, "hotspot-agent-registry.txt").getAbsolutePath());
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        return paths;
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
