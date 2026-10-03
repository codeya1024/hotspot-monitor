package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import net.bytebuddy.asm.Advice;

/**
 * Spring RestTemplate 5 参 doExecute(URI, String uriTemplate, HttpMethod, RequestCallback, ResponseExtractor) 插桩。
 * Spring 6.1+ 中 public execute(URI,...) 会转发到该重载，4 参 doExecute 已不被调用。
 */
public class RestTemplate5Advice {

    @Advice.OnMethodEnter
    public static long enter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Argument(0) java.net.URI url,
                     @Advice.Argument(2) Object httpMethod,
                     @Advice.Enter long start,
                     @Advice.Thrown Throwable t) {
        try {
            String m = httpMethod == null ? "?" : httpMethod.toString();
            String u = url == null ? "?" : url.toString();
            SpanStore.INSTANCE.recordChild("http", truncate(m + " " + u), start, t);
        } catch (Throwable ignore) {
        }
    }

    public static String truncate(String s) {
        return s.length() > 8000 ? s.substring(0, 8000) + "..." : s;
    }
}
