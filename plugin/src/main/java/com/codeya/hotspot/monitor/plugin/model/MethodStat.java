package com.codeya.hotspot.monitor.plugin.model;

import com.google.gson.annotations.SerializedName;

/** 一个方法在请求内的耗时聚合 */
public class MethodStat {

    public String name;
    public long calls;
    @SerializedName("selfNanos")
    public long selfNanos;
    @SerializedName("totalNanos")
    public long totalNanos;
    @SerializedName("maxNanos")
    public long maxNanos;

    public double selfMs() {
        return selfNanos / 1_000_000.0;
    }

    public double totalMs() {
        return totalNanos / 1_000_000.0;
    }

    public double maxMs() {
        return maxNanos / 1_000_000.0;
    }
}
