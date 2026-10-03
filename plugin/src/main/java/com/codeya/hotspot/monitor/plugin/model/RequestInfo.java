package com.codeya.hotspot.monitor.plugin.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** 一次 HTTP 请求 */
public class RequestInfo {

    public long id;
    @SerializedName("startAtMs")
    public long startAtMs;
    public String method;
    public String path;
    public int status;
    public String thread;
    public String error;
    @SerializedName("totalNanos")
    public long totalNanos;
    @SerializedName("sqlCount")
    public long sqlCount;
    @SerializedName("sqlNanos")
    public long sqlNanos;
    @SerializedName("httpCount")
    public long httpCount;
    @SerializedName("httpNanos")
    public long httpNanos;
    public List<SpanInfo> spans;
    /** 方法时间线事件（兼容旧 agent；新 agent 不再输出，为 null） */
    @SerializedName("methodEvents")
    public List<MethodEvent> methodEvents;
    /** 方法耗时聚合（兼容旧 agent；新 agent 不再输出，为 null） */
    public List<MethodStat> methods;
    /** 方法调用树（新 agent 输出）：方法 → 下级方法 → 每层耗时 */
    @SerializedName("methodTree")
    public MethodNodeInfo methodTree;

    public double totalMs() {
        return totalNanos / 1_000_000.0;
    }
}
