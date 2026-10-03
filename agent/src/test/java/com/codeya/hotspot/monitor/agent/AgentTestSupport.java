package com.codeya.hotspot.monitor.agent;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.ByteBuddyAgent;

/**
 * 测试基建：把 agent 挂到当前测试 JVM（等价于 -javaagent 的真实加载路径）。
 * 首次调用后所有后续类加载都会被插桩。
 */
public final class AgentTestSupport {

    private static volatile boolean installed = false;

    private AgentTestSupport() {
    }

    public synchronized static void install() {
        if (installed) {
            return;
        }
        Instrumentation inst = ByteBuddyAgent.install();
        Agent.premain("port=0,ring=200,slowSqlMs=10,slowHttpMs=10", inst);
        installed = true;
    }
}
