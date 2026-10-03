package com.codeya.hotspot.monitor.agent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一次 HTTP 请求的完整时间线（XRebel 式“请求级瀑布”的根节点）。
 * 方法耗时按调用关系聚合成一棵方法树（methodRoot），SQL/HTTP 片段保存在 spans。
 */
public final class RequestSpan {

    public final long id;
    public final long startNanos; // System.nanoTime()，用于算耗时与子 span 偏移
    public final long startAtMs;  // System.currentTimeMillis()，用于展示时间
    public final String method;
    public final String path;
    public final String thread;
    /** 方法调用树根节点（名字 = "METHOD path"），子节点按调用关系挂载 */
    public final MethodNode methodRoot;
    public volatile int status;
    public volatile String error;   // 异常类名，无异常为 null
    public volatile long totalNanos;

    private final List<ChildSpan> spans = new CopyOnWriteArrayList<>();

    public RequestSpan(long id, String method, String path) {
        this.id = id;
        this.method = method == null ? "?" : method;
        this.path = path == null ? "?" : path;
        this.thread = Thread.currentThread().getName();
        this.startNanos = System.nanoTime();
        this.startAtMs = System.currentTimeMillis();
        this.methodRoot = new MethodNode((this.method + " " + this.path).trim());
    }

    public void addSpan(ChildSpan c) {
        spans.add(c);
    }

    public List<ChildSpan> spans() {
        return spans;
    }

    public long sqlCount() {
        long n = 0;
        for (ChildSpan c : spans) {
            if ("sql".equals(c.kind)) n++;
        }
        return n;
    }

    public long sqlNanos() {
        long n = 0;
        for (ChildSpan c : spans) {
            if ("sql".equals(c.kind)) n += c.nanos;
        }
        return n;
    }

    public long httpCount() {
        long n = 0;
        for (ChildSpan c : spans) {
            if ("http".equals(c.kind)) n++;
        }
        return n;
    }

    public long httpNanos() {
        long n = 0;
        for (ChildSpan c : spans) {
            if ("http".equals(c.kind)) n += c.nanos;
        }
        return n;
    }
}
