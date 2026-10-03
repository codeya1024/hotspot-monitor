package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.MethodTracker;
import com.codeya.hotspot.monitor.agent.core.RequestContext;
import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.model.RequestSpan;
import net.bytebuddy.asm.Advice;

/**
 * javax.servlet 栈（Boot 2 / JDK 8 时代项目）的请求入口插桩。
 */
public class JavaxServletAdvice {

    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(0) javax.servlet.ServletRequest req) {
        try {
            String method = "?";
            String path = "?";
            if (req instanceof javax.servlet.http.HttpServletRequest) {
                javax.servlet.http.HttpServletRequest h = (javax.servlet.http.HttpServletRequest) req;
                method = h.getMethod();
                path = h.getRequestURI();
            }
            RequestContext.begin(method, path);
        } catch (Throwable ignore) {
            // 绝不影响业务
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Argument(1) javax.servlet.ServletResponse res,
                     @Advice.Thrown Throwable t) {
        try {
            RequestSpan s = RequestContext.current();
            if (s == null) {
                return;
            }
            if (res instanceof javax.servlet.http.HttpServletResponse) {
                s.status = ((javax.servlet.http.HttpServletResponse) res).getStatus();
            }
            if (t != null) {
                s.error = t.getClass().getSimpleName();
            }
            s.totalNanos = System.nanoTime() - s.startNanos;
            SpanStore.INSTANCE.recordRequest(s);
            RequestContext.end();
            MethodTracker.clearStack();
        } catch (Throwable ignore) {
            RequestContext.end();
            MethodTracker.clearStack();
        }
    }
}
