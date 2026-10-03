package com.codeya.hotspot.monitor.agent.core;

import com.codeya.hotspot.monitor.agent.model.RequestSpan;

/**
 * 请求上下文：把同一线程内的 SQL / 下游 HTTP 片段挂到正在处理的请求上。
 * 注意：跨线程的异步调用（线程池等）在 v1 里不会关联到原请求，会落到“孤立片段”。
 */
public final class RequestContext {

    private static final ThreadLocal<RequestSpan> CURRENT = new ThreadLocal<>();

    private RequestContext() {
    }

    public static RequestSpan begin(String method, String path) {
        RequestSpan s = new RequestSpan(SpanStore.INSTANCE.nextId(), method, path);
        CURRENT.set(s);
        return s;
    }

    public static RequestSpan current() {
        return CURRENT.get();
    }

    public static void end() {
        CURRENT.remove();
    }
}
