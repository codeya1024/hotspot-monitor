package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import net.bytebuddy.asm.Advice;

/**
 * Spring WebClient（Reactor）下游调用插桩。
 * <p>
 * 插桩 ExchangeFunction.exchange(ClientRequest)（所有 WebClient 请求必经，
 * 实现类为 ExchangeFunctions$DefaultExchangeFunction）：enter 记起点，
 * exit 拿到返回的 Mono 后挂 doOnSuccess/doOnError 回调——实际请求发生在
 * Mono 订阅（subscribe/block）时，耗时 = enter 到 Mono 完成。
 * <p>
 * label 取 method()/url() 用反射（不引入 spring-webflux 依赖）；@Advice.Return
 * 必须声明为 Mono（擦除类型）——readOnly=false 的返回值要求可赋值给目标返回类型，
 * Object 会被 Byte Buddy 拒绝。agent 仅引入 provided reactor-core（编译期，不打包）。
 * 回调用显式嵌套类而非 lambda——Byte Buddy Advice 复制方法体字节码，
 * invokedynamic（lambda）不受支持。
 * <p>
 * 归属说明：同步 block() 时完成回调跑在请求线程（有请求上下文 → 挂请求）；
 * 纯异步订阅时回调在 Reactor 线程（无请求上下文 → 落入"无主SQL/HTTP"）。
 */
public class WebClientAdvice {

    @Advice.OnMethodEnter
    static long enter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit
    public static void exit(@Advice.Enter long start,
                     @Advice.Argument(0) Object req,
                     @Advice.Return(readOnly = false) reactor.core.publisher.Mono mono) {
        try {
            if (mono == null) {
                return;
            }
            String label = labelOf(req);
            final String lbl = truncate(label);
            final long s = start;
            // 挂完成/错误回调：实际请求发生在 Mono 订阅（subscribe/block）时
            mono = mono.doOnSuccess(new OnOk(lbl, s)).doOnError(new OnErr(lbl, s));
        } catch (Throwable ignore) {
        }
    }

    /** 反射取 "GET http://host/path"：ClientRequest.method()（HttpMethod 枚举）+ url()（URI） */
    public static String labelOf(Object req) {
        if (req == null) {
            return "?";
        }
        try {
            java.lang.reflect.Method mm = req.getClass().getMethod("method");
            java.lang.reflect.Method um = req.getClass().getMethod("url");
            mm.setAccessible(true);
            um.setAccessible(true);
            return mm.invoke(req) + " " + um.invoke(req);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 成功回调：在订阅线程执行（同步 block 时即请求线程，可归属请求） */
    public static final class OnOk implements java.util.function.Consumer<Object> {
        private final String label;
        private final long start;

        public OnOk(String label, long start) {
            this.label = label;
            this.start = start;
        }

        @Override
        public void accept(Object r) {
            try {
                SpanStore.INSTANCE.recordChild("http", label, start, null);
            } catch (Throwable ignore) {
            }
        }
    }

    /** 失败回调 */
    public static final class OnErr implements java.util.function.Consumer<Throwable> {
        private final String label;
        private final long start;

        public OnErr(String label, long start) {
            this.label = label;
            this.start = start;
        }

        @Override
        public void accept(Throwable t) {
            try {
                SpanStore.INSTANCE.recordChild("http", label, start, t);
            } catch (Throwable ignore) {
            }
        }
    }

    public static String truncate(String s) {
        return s.length() > 8000 ? s.substring(0, 8000) + "..." : s;
    }
}
