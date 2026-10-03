package com.codeya.hotspot.monitor.agent.core;

/**
 * 捕获业务调用来源：在记录 SQL/HTTP 片段时取当前线程栈，
 * 过滤掉 JDK / 框架 / 代理 / 连接池等噪音帧，返回最多 5 个业务帧。
 * 结果形如 "Mapper.selectXxx(34) ← ServiceImpl.findApp(120) ← Controller.find(80)"。
 */
public final class CallStack {

    private static final String[] SKIP = {
            "com.codeya.hotspot.monitor.agent", "java.", "javax.", "jakarta.", "jdk.", "sun.",
            "org.springframework.web", "org.springframework.jdbc", "org.springframework.transaction", "org.springframework.aop",
            "org.apache.", "net.bytebuddy", "okhttp3.internal", "org.h2", "org.hibernate",
            "com.zaxxer.hikari", "com.alibaba.druid", "org.apache.tomcat.jdbc",
            "org.apache.commons.dbcp", "com.mchange.v2.c3p0", "oracle.ucp",
            "org.apache.ibatis", "com.baomidou", "org.mybatis", "reactor."
    };

    private CallStack() {
    }

    public static String callerOf() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = null;
            int n = 0;
            for (int i = 1; i < st.length && n < 5; i++) {
                StackTraceElement e = st[i];
                String cn = e.getClassName();
                boolean skip = false;
                for (String p : SKIP) {
                    if (cn.startsWith(p)) {
                        skip = true;
                        break;
                    }
                }
                if (skip) {
                    continue;
                }
                if (sb == null) {
                    sb = new StringBuilder();
                } else {
                    sb.append(" ← ");
                }
                int dot = cn.lastIndexOf('.');
                String simple = dot >= 0 ? cn.substring(dot + 1) : cn;
                sb.append(simple).append('.').append(e.getMethodName()).append('(').append(e.getLineNumber()).append(')');
                n++;
            }
            return sb == null ? null : sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
