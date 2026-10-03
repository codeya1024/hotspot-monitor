package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import net.bytebuddy.asm.Advice;

/**
 * Spring RestTemplate.doExecute(URI, HttpMethod, RequestCallback, ResponseExtractor) 插桩，
 * 覆盖所有 RestTemplate 请求的耗时与下游地址。
 */
public class RestTemplateAdvice {

    @Advice.OnMethodEnter
    public static long enter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Argument(0) java.net.URI url,
                     @Advice.Argument(1) Object httpMethod,
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
