package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import net.bytebuddy.asm.Advice;

/**
 * Apache HttpClient（4.x / 5.x）同步调用插桩。
 * <p>
 * 匹配 org.apache.http.impl.client.InternalHttpClient（4.x）与
 * org.apache.hc.client5.impl.classic.InternalHttpClient（5.x）的
 * execute(request[, context]) 重载（1/2 参数），第一个参数是请求对象。
 * 取 method/url 用反射（getMethod + getUri/getURI），不依赖 httpclient 编译类型——
 * advice 参数保持 Object，Byte Buddy 解析 advice 类时零外部类型依赖。
 */
public class ApacheHttpClientAdvice {

    @Advice.OnMethodEnter
    static long enter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Argument(0) Object req,
                     @Advice.Enter long start,
                     @Advice.Thrown Throwable t) {
        try {
            SpanStore.INSTANCE.recordChild("http", truncate(labelOf(req)), start, t);
        } catch (Throwable ignore) {
        }
    }

    /** 反射取 "GET http://host/path"；取不到返回 "?" */
    public static String labelOf(Object req) {
        if (req == null) {
            return "?";
        }
        try {
            String method = null;
            Object uri = null;
            for (java.lang.reflect.Method md : req.getClass().getMethods()) {
                if (md.getParameterCount() != 0) {
                    continue;
                }
                String n = md.getName();
                if ("getMethod".equals(n)) {
                    Object v = md.invoke(req);
                    if (v != null) {
                        method = v.toString();
                    }
                } else if (("getUri".equals(n) || "getURI".equals(n)) && uri == null) {
                    uri = md.invoke(req);
                }
            }
            return (method == null ? "?" : method) + " " + (uri == null ? "?" : uri);
        } catch (Throwable t) {
            return "?";
        }
    }

    public static String truncate(String s) {
        return s.length() > 8000 ? s.substring(0, 8000) + "..." : s;
    }
}
