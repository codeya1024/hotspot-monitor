package com.codeya.hotspot.monitor.agent;

/**
 * Agent 配置：默认值 + 系统属性（-Dhotspot.xxx）+ -javaagent 参数（k=v,k=v）三级覆盖。
 */
public final class Config {

    /** 本地上报服务端口；0 表示随机端口 */
    public int port = 28765;
    /** 请求环形缓冲条数 */
    public int ring = 500;
    /** 慢 SQL 阈值（毫秒） */
    public int slowSqlMs = 200;
    /** 慢下游 HTTP 阈值（毫秒） */
    public int slowHttpMs = 300;
    /** 方法级计时的类名前缀（逗号分隔）；空 = 不插桩方法。默认取主类包名 */
    public String packages = "";
    /** 方法树噪音过滤：单次调用最大耗时 < 该值（毫秒）且不含 SQL 的方法整棵子树忽略；0 = 关闭 */
    public int noiseThresholdMs = 5;
    /** 方法计时排除的类名前缀（逗号分隔）：不插桩 JDK/框架/容器类，避免高频框架方法挤爆树与插桩开销。
     *  默认排除常见框架；业务包（如 com.example.app）不受影响。可配 -Dhotspot.excludePackages= 清空。 */
    public String excludePackages = DEFAULT_EXCLUDES;

    /** 默认排除：JDK / Servlet / Spring / Netty / Druid / MyBatis / Tomcat / 连接池 / 日志 / Byte Buddy 等 */
    public static final String DEFAULT_EXCLUDES =
            "java.,javax.,jakarta.,jdk.,sun.,com.sun.,"
                    + "org.springframework.,org.apache.,io.netty.,com.alibaba.,ch.qos.,org.slf4j.,"
                    + "net.bytebuddy.,org.mybatis.,org.h2.,org.hibernate.,reactor.,"
                    + "com.zaxxer.,com.mchange.,oracle.ucp";

    public static Config from(String agentArgs) {
        Config c = new Config();
        c.port = intProp("hotspot.port", c.port);
        c.ring = intProp("hotspot.ring", c.ring);
        c.slowSqlMs = intProp("hotspot.slowSqlMs", c.slowSqlMs);
        c.slowHttpMs = intProp("hotspot.slowHttpMs", c.slowHttpMs);
        c.noiseThresholdMs = intProp("hotspot.noiseThresholdMs", c.noiseThresholdMs);
        // 显式设 -Dhotspot.excludePackages=（空串）→ 不排除任何包（全量方法计时）
        String exProp = System.getProperty("hotspot.excludePackages");
        if (exProp != null) {
            c.excludePackages = exProp;
        }
        // 未设置 -Dhotspot.packages（null）→ 可自动推导；显式设空串 → 关闭方法计时
        String pkProp = System.getProperty("hotspot.packages");
        c.packages = pkProp == null ? "" : pkProp;
        boolean packagesExplicit = pkProp != null;
        if (agentArgs != null && !agentArgs.trim().isEmpty()) {
            for (String kv : agentArgs.split(",")) {
                int i = kv.indexOf('=');
                if (i <= 0) continue;
                String k = kv.substring(0, i).trim();
                String v = kv.substring(i + 1).trim();
                if ("port".equals(k)) c.port = parseInt(v, c.port);
                else if ("ring".equals(k)) c.ring = parseInt(v, c.ring);
                else if ("slowSqlMs".equals(k)) c.slowSqlMs = parseInt(v, c.slowSqlMs);
                else if ("slowHttpMs".equals(k)) c.slowHttpMs = parseInt(v, c.slowHttpMs);
                else if ("noiseThresholdMs".equals(k)) c.noiseThresholdMs = parseInt(v, c.noiseThresholdMs);
                else if ("excludePackages".equals(k)) c.excludePackages = v;
                else if ("packages".equals(k)) {
                    // agent 参数按逗号分隔，多包需用分号（-Dhotspot.packages 支持逗号，不受此限制）
                    c.packages = v.replace(';', ',');
                    packagesExplicit = true;
                }
            }
        }
        // 仅当用户既没配 -D 也没配 agent 参数时才自动推导；
        // 显式空串（-Dhotspot.packages= 或 packages=）表示关闭方法计时
        if (c.packages.trim().isEmpty() && !packagesExplicit) {
            c.packages = derivePackages();
        }
        return c;
    }

    private static int intProp(String key, int def) {
        String v = System.getProperty(key);
        return v == null ? def : parseInt(v, def);
    }

    /** 从主类自动推导方法计时包名；测试/工具主类不推导（返回空 = 关闭方法计时） */
    private static String derivePackages() {
        try {
            String cmd = System.getProperty("sun.java.command", "");
            String main = cmd.trim().split("\\s+")[0];
            if (main.isEmpty() || main.startsWith("org.") || main.startsWith("com.intellij")
                    || main.startsWith("java.") || main.contains("surefire") || main.contains("junit")) {
                return "";
            }
            int i = main.lastIndexOf('.');
            if (i <= 0) {
                return "";
            }
            // 取主类包名前两段（如 com.example.app.web.Main → com.example），
            // 覆盖整个应用域的所有业务模块（controller/service/mapper 等）。
            // 若包名仅两段（如 com.j8test.J8Main → com.j8test），直接取完整包名。
            String pkg = main.substring(0, i);
            String[] parts = pkg.split("\\.");
            if (parts.length <= 2) {
                return pkg;
            }
            return parts[0] + "." + parts[1];
        } catch (Throwable t) {
            return "";
        }
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
