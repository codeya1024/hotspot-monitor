package com.codeya.hotspot.monitor.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Java Agent 入口。
 * - premain：-javaagent 启动挂载
 * - agentmain：运行时 Attach（预留，供插件后续一键挂载）
 *
 * 挂载流程：
 * 1. 找到自身 jar，追加到系统类加载器搜索路径（保证被插桩应用的各类加载器
 *    都能解析到 advice / 模型类）；
 * 2. 安装 Byte Buddy 变换器并启动本地上报服务。
 */
public final class Agent {

    private Agent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst, "premain");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst, "agentmain");
    }

    private static void install(String agentArgs, Instrumentation inst, String mode) {
        long t0 = System.currentTimeMillis();
        try {
            File ownJar = locateOwnJar();
            if (ownJar != null && ownJar.isFile() && ownJar.getName().endsWith(".jar")) {
                inst.appendToSystemClassLoaderSearch(new JarFile(ownJar));
            }
            Config cfg = Config.from(agentArgs);
            int port = AgentRuntime.install(inst, cfg);
            System.out.println("[hotspot-agent] " + mode + " OK (" + (System.currentTimeMillis() - t0) + "ms)"
                    + " reportPort=" + port
                    + " slowSqlMs=" + cfg.slowSqlMs + "ms slowHttpMs=" + cfg.slowHttpMs + "ms");
        } catch (Throwable t) {
            System.err.println("[hotspot-agent] init failed: " + t);
            t.printStackTrace();
        }
    }

    private static File locateOwnJar() {
        try {
            return new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Throwable t) {
            return null;
        }
    }
}
