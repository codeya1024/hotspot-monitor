package com.codeya.hotspot.monitor.agent;

import com.codeya.hotspot.monitor.agent.advice.JakartaServletAdvice;
import com.codeya.hotspot.monitor.agent.advice.JavaxServletAdvice;
import com.codeya.hotspot.monitor.agent.advice.JdbcConnectionNoSqlAdvice;
import com.codeya.hotspot.monitor.agent.advice.JdbcConnectionSqlAdvice;
import com.codeya.hotspot.monitor.agent.advice.JdbcStatementNoSqlAdvice;
import com.codeya.hotspot.monitor.agent.advice.JdbcStatementSqlAdvice;
import com.codeya.hotspot.monitor.agent.advice.MapperProxyAdvice;
import com.codeya.hotspot.monitor.agent.advice.MethodAdvice;
import com.codeya.hotspot.monitor.agent.advice.ApacheHttpClientAdvice;
import com.codeya.hotspot.monitor.agent.advice.OkHttpAdvice;
import com.codeya.hotspot.monitor.agent.advice.RestTemplate5Advice;
import com.codeya.hotspot.monitor.agent.advice.RestTemplateAdvice;
import com.codeya.hotspot.monitor.agent.advice.WebClientAdvice;
import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.report.Registry;
import com.codeya.hotspot.monitor.agent.report.Reporter;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatcher;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Agent 运行时：注册插桩变换器 + 启动本地上报服务。
 */
public final class AgentRuntime {

    private static volatile boolean transformersInstalled = false;
    private static volatile boolean methodTransformersInstalled = false;
    private static volatile Reporter reporter;

    private AgentRuntime() {
    }

    public synchronized static int install(Instrumentation inst, Config cfg) throws Exception {
        SpanStore.INSTANCE.configure(cfg);
        // 启动自检：预读所有 advice 类字节码到内存。agent jar 若损坏（拷贝/同步中断），
        // 在这里立刻给出明确错误并跳过插桩，而不是运行期让 Byte Buddy 反复读坏数据（可能崩 JVM/SIGBUS）。
        ClassFileLocator adviceLocator = buildAdviceLocator();
        if (adviceLocator == null) {
            System.err.println("[hotspot-agent] FATAL: cannot read advice bytecode; hotspot-agent.jar seems corrupted.");
            System.err.println("[hotspot-agent]        Re-copy it with `cp` (avoid drag-drop / cloud sync / IDE copy).");
            System.err.println("[hotspot-agent]        No instrumentation installed; service starts normally (monitoring unavailable).");
            return -1;
        }
        if (!transformersInstalled) {
            installTransformers(inst, adviceLocator);
            transformersInstalled = true;
        }
        if (!methodTransformersInstalled && cfg.packages != null && !cfg.packages.trim().isEmpty()) {
            installMethodTransformers(inst, cfg, adviceLocator);
            methodTransformersInstalled = true;
        }
        if (reporter != null) {
            reporter.stop();
        }
        reporter = new Reporter(cfg);
        reporter.start();
        return reporter.port();
    }

    /** 所有 advice 类 */
    private static final Class<?>[] ADVICE_CLASSES = {
            JavaxServletAdvice.class, JakartaServletAdvice.class,
            JdbcConnectionSqlAdvice.class, JdbcConnectionNoSqlAdvice.class,
            JdbcStatementSqlAdvice.class, JdbcStatementNoSqlAdvice.class,
            RestTemplateAdvice.class, RestTemplate5Advice.class, OkHttpAdvice.class,
            ApacheHttpClientAdvice.class, WebClientAdvice.class,
            MethodAdvice.class, MapperProxyAdvice.class
    };

    /**
     * 预读 advice 字节码并缓存为内存 locator：运行期 Advice.to 不再从 classloader/jar 读，
     * 既避免 jar 损坏导致的运行期崩溃，也省去每次 transform 的资源读取。
     * 任一 advice 读不到（jar 损坏）返回 null。
     */
    private static ClassFileLocator buildAdviceLocator() {
        try {
            Map<String, byte[]> map = new HashMap<String, byte[]>();
            for (Class<?> a : ADVICE_CLASSES) {
                byte[] bytes = readClassBytes(a);
                if (bytes == null) {
                    return null;
                }
                map.put(a.getName(), bytes);
            }
            return new ClassFileLocator.Simple(map);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readClassBytes(Class<?> c) {
        InputStream in = c.getResourceAsStream("/" + c.getName().replace('.', '/') + ".class");
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 当前进程 pid（供插件识别） */
    public static String pid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            return at > 0 ? name.substring(0, at) : name;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 当前应用名（取启动主类，兼容 JDK8/21/25） */
    public static String appName() {
        String cmd = System.getProperty("sun.java.command");
        if (cmd != null && !cmd.isEmpty()) {
            String head = cmd.trim().split("\\s+")[0];
            int slash = Math.max(head.lastIndexOf('/'), head.lastIndexOf('\\'));
            return slash >= 0 ? head.substring(slash + 1) : head;
        }
        return "?";
    }

    /**
     * 异常安全的"子类型"匹配器：Byte Buddy 评估 isSubTypeOf 需要解析目标类的完整继承树。
     * 当应用 classpath 存在"半残缺类"（如 Micrometer 的 Jersey 绑定类引用了未引入的
     * org.glassfish.jersey 接口）时，解析会抛 NoSuchTypeException。这里把解析失败视为"不匹配"，
     * 避免启动期刷 Byte Buddy ERROR 栈（该类本就不是插桩目标）。
     */
    private static ElementMatcher.Junction<TypeDescription> safeSubTypeOf(final Class<?> type) {
        return new ElementMatcher.Junction<TypeDescription>() {
            @Override
            public boolean matches(TypeDescription target) {
                try {
                    return target.isAssignableTo(type);
                } catch (Throwable t) {
                    // 继承树解析失败（引用了 classpath 外的父类型，如 Micrometer 的 Jersey 绑定类）
                    // → 视为不匹配，避免启动期刷 Byte Buddy ERROR 栈
                    return false;
                }
            }

            @Override
            public <U extends TypeDescription> ElementMatcher.Junction<U> and(ElementMatcher<? super U> other) {
                return new ElementMatcher.Junction.Conjunction<U>(this, other);
            }

            @Override
            public <U extends TypeDescription> ElementMatcher.Junction<U> or(ElementMatcher<? super U> other) {
                return new ElementMatcher.Junction.Disjunction<U>(this, other);
            }
        };
    }

    /** safeSubTypeOf 的字符串版本：按类型名遍历继承树（含接口层级），解析失败视为不匹配。
     * 用于不引入编译依赖的类型（如 spring-webflux 的 ExchangeFunction）。 */
    private static ElementMatcher.Junction<TypeDescription> safeSubTypeOf(final String typeName) {
        return new ElementMatcher.Junction<TypeDescription>() {
            @Override
            public boolean matches(TypeDescription target) {
                try {
                    return hierarchyContains(target.asGenericType(), typeName);
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override
            public <U extends TypeDescription> ElementMatcher.Junction<U> and(ElementMatcher<? super U> other) {
                return new ElementMatcher.Junction.Conjunction<U>(this, other);
            }

            @Override
            public <U extends TypeDescription> ElementMatcher.Junction<U> or(ElementMatcher<? super U> other) {
                return new ElementMatcher.Junction.Disjunction<U>(this, other);
            }

            private boolean hierarchyContains(TypeDescription.Generic cur, String name) {
                try {
                    while (cur != null) {
                        if (name.equals(cur.asErasure().getName())) {
                            return true;
                        }
                        for (TypeDescription.Generic it : cur.getInterfaces()) {
                            if (hierarchyContains(it, name)) {
                                return true;
                            }
                        }
                        cur = cur.getSuperClass();
                    }
                } catch (Throwable t) {
                    // 继承树解析失败 → 视为不匹配
                }
                return false;
            }
        };
    }

    private static void installTransformers(Instrumentation inst, ClassFileLocator adviceLocator) {
        // 排除常见连接池代理类（它们也会实现 java.sql.Connection/Statement，
        // 只对底层驱动插桩，避免同一 SQL 重复计数）
        ElementMatcher<TypeDescription> poolExclude = not(
                nameStartsWith("com.zaxxer.hikari")
                        .or(nameStartsWith("com.alibaba.druid"))
                        .or(nameStartsWith("org.apache.tomcat.jdbc"))
                        .or(nameStartsWith("org.apache.commons.dbcp"))
                        .or(nameStartsWith("com.mchange.v2.c3p0"))
                        .or(nameStartsWith("oracle.ucp")));

        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                // 对已加载的类（如测试环境/IDE 预加载的 servlet 容器类）做重变换，
                // 保证 -javaagent 启动后已加载的框架类也能被插桩。
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly());

        builder
                // ---- HTTP 入口：javax（老栈）----
                .type(named("javax.servlet.http.HttpServlet"))
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(JavaxServletAdvice.class, adviceLocator)
                        .on(named("service").and(takesArguments(2)))))
                // ---- HTTP 入口：jakarta（Boot 3 栈）----
                .type(named("jakarta.servlet.http.HttpServlet"))
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(JakartaServletAdvice.class, adviceLocator)
                        .on(named("service").and(takesArguments(2)))))
                // ---- JDBC：Connection（prepareStatement/prepareCall/createStatement）----
                .type(safeSubTypeOf(java.sql.Connection.class).and(not(isInterface())).and(poolExclude))
                .transform((b, td, cl, m, pd) -> b
                        .visit(Advice.to(JdbcConnectionSqlAdvice.class, adviceLocator).on(
                                named("prepareStatement").and(takesArguments(1)).and(takesArgument(0, String.class))
                                        .or(named("prepareCall").and(takesArguments(1)).and(takesArgument(0, String.class)))))
                        .visit(Advice.to(JdbcConnectionNoSqlAdvice.class, adviceLocator).on(
                                named("createStatement").and(takesArguments(0)))))
                // ---- JDBC：Statement（execute*/executeQuery/executeUpdate/executeBatch）----
                .type(safeSubTypeOf(java.sql.Statement.class).and(not(isInterface())).and(poolExclude))
                .transform((b, td, cl, m, pd) -> b
                        .visit(Advice.to(JdbcStatementNoSqlAdvice.class, adviceLocator).on(
                                named("execute").and(takesArguments(0))
                                        .or(named("executeQuery").and(takesArguments(0)))
                                        .or(named("executeUpdate").and(takesArguments(0)))
                                        .or(named("executeLargeUpdate").and(takesArguments(0)))
                                        .or(named("executeBatch").and(takesArguments(0)))))
                        .visit(Advice.to(JdbcStatementSqlAdvice.class, adviceLocator).on(
                                named("execute").and(takesArguments(1)).and(takesArgument(0, String.class))
                                        .or(named("executeQuery").and(takesArguments(1)).and(takesArgument(0, String.class)))
                                        .or(named("executeUpdate").and(takesArguments(1)).and(takesArgument(0, String.class)))
                                        .or(named("executeLargeUpdate").and(takesArguments(1)).and(takesArgument(0, String.class))))))
                // ---- 下游 HTTP：Spring RestTemplate（4 参/5 参 doExecute 都覆盖）----
                .type(named("org.springframework.web.client.RestTemplate"))
                .transform((b, td, cl, m, pd) -> b
                        .visit(Advice.to(RestTemplateAdvice.class, adviceLocator)
                                .on(named("doExecute").and(takesArguments(4))))
                        .visit(Advice.to(RestTemplate5Advice.class, adviceLocator)
                                .on(named("doExecute").and(takesArguments(5)))))
                // ---- 下游 HTTP：OkHttp（同步 execute，4.x 路径）----
                .type(named("okhttp3.internal.connection.RealCall"))
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(OkHttpAdvice.class, adviceLocator)
                        .on(named("execute").and(takesArguments(0)))))
                // ---- 下游 HTTP：Apache HttpClient 4.x / 5.x ----
                // 4.x 的 execute(HttpUriRequest, HttpContext) 实现在父类 CloseableHttpClient（子类只实现
                // doExecute），5.x 的 execute(ClassicHttpRequest, HttpContext) 在 InternalHttpClient。
                // 只插 2 参重载：1 参 execute 会转调 2 参，避免同一请求记两次。
                .type(named("org.apache.http.impl.client.CloseableHttpClient")
                        .or(named("org.apache.hc.client5.impl.classic.InternalHttpClient")))
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(ApacheHttpClientAdvice.class, adviceLocator)
                        .on(named("execute").and(takesArguments(2)))))
                // ---- 下游 HTTP：Spring WebClient（ExchangeFunction.exchange，异步 Mono 完成回调）----
                // 先按包名前缀缩小范围再走 hasSuperType：全量 hasSuperType 会为每个加载的类解析完整
                // 接口树，遇到引用 classpath 外类型的"半残缺类"（如 Micrometer 的 Jersey 绑定类）时
                // 抛 NoSuchTypeException 刷 Byte Buddy ERROR；ExchangeFunction 的实现类都在该包内，不漏。
                .type(not(isInterface())
                        .and(nameStartsWith("org.springframework.web.reactive.function.client."))
                        .and(hasSuperType(safeSubTypeOf("org.springframework.web.reactive.function.client.ExchangeFunction"))))
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(WebClientAdvice.class,
                        new ClassFileLocator.Compound(adviceLocator, ClassFileLocator.ForClassLoader.of(cl)))
                        .on(named("exchange").and(takesArguments(1))
                                .and(takesArgument(0, named("org.springframework.web.reactive.function.client.ClientRequest"))))))
                // ---- MyBatis Mapper 代理：丢失的 Mapper 接口方法名这一层（Pinpoint 同款）----
                .type(named("org.apache.ibatis.binding.MapperProxy"))
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(MapperProxyAdvice.class, adviceLocator)
                        .on(named("invoke").and(takesArguments(3)))))
                .installOn(inst);
    }

    /**
     * 方法级计时变换器（hotspot.packages 配置的类名前缀，逗号分隔）：
     * 插桩包内所有非接口、非抽象、非构造方法，请求内聚合自身耗时。
     * 与主变换器分离安装，便于测试时先装主变换器再补装方法变换器。
     */
    private static void installMethodTransformers(Instrumentation inst, Config cfg, ClassFileLocator adviceLocator) {
        ElementMatcher.Junction<TypeDescription> pkg = null;
        for (String p : cfg.packages.split(",")) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            ElementMatcher.Junction<TypeDescription> m = nameStartsWith(t);
            pkg = pkg == null ? m : pkg.or(m);
        }
        if (pkg == null) {
            return;
        }
        // 框架排除：默认不插桩 JDK/Servlet/Spring/Druid/MyBatis/Tomcat/Netty/连接池/日志等
        // （对标 XRebel：方法计时只覆盖用户代码，框架类不插桩，避免高频框架方法挤爆树与额外开销）
        ElementMatcher.Junction<TypeDescription> exclude = null;
        for (String e : cfg.excludePackages.split(",")) {
            String t = e.trim();
            if (t.isEmpty()) {
                continue;
            }
            ElementMatcher.Junction<TypeDescription> m = nameStartsWith(t);
            exclude = exclude == null ? m : exclude.or(m);
        }
        ElementMatcher.Junction<TypeDescription> typeMatcher = pkg
                .and(not(isInterface()))
                .and(not(isSynthetic()))
                .and(not(nameStartsWith("com.codeya.hotspot.monitor.agent")))
                .and(not(nameStartsWith("java.")))
                .and(not(nameStartsWith("jdk.")));
        if (exclude != null) {
            typeMatcher = typeMatcher.and(not(exclude));
        }
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .type(typeMatcher)
                .transform((b, td, cl, m, pd) -> b.visit(Advice.to(MethodAdvice.class, adviceLocator)
                        .on(isMethod()
                                .and(not(isConstructor()))
                                .and(not(isTypeInitializer()))
                                .and(not(isAbstract())))))
                .installOn(inst);
    }
}
