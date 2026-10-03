package com.codeya.hotspot.monitor.plugin.model;

import com.google.gson.annotations.SerializedName;

/** 端点聚合统计 */
public class EndpointStat {

    public String name;
    public long count;
    @SerializedName("avgNanos")
    public long avgNanos;
    @SerializedName("p50Nanos")
    public long p50Nanos;
    @SerializedName("p95Nanos")
    public long p95Nanos;
    @SerializedName("maxNanos")
    public long maxNanos;
    @SerializedName("lastAtMs")
    public long lastAtMs;
}
