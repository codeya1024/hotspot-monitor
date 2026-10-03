package com.codeya.hotspot.monitor.agent.model;

/**
 * 请求内部的一个耗时片段：一条 SQL，或一次下游 HTTP 调用。
 */
public final class ChildSpan {

    /** "sql" | "http" */
    public final String kind;
    /** SQL 文本（截断）或 "METHOD url" */
    public final String label;
    public final long startNanos; // 相对 JVM 启动的 nanoTime，可换算相对请求起点的偏移
    public final long nanos;
    public final boolean ok;
    /** 业务调用来源（"Mapper.selectXxx(34) ← ServiceImpl.find(120)"），SQL/HTTP 片段记录 */
    public final String caller;
    /** 发生时刻（wall-clock epoch ms，span 完成时近似记录） */
    public final long occurredAtMs;

    public ChildSpan(String kind, String label, long startNanos, long nanos, boolean ok, String caller) {
        this.kind = kind;
        this.label = label == null ? "?" : label;
        this.startNanos = startNanos;
        this.nanos = nanos;
        this.ok = ok;
        this.caller = caller;
        this.occurredAtMs = System.currentTimeMillis();
    }
}
