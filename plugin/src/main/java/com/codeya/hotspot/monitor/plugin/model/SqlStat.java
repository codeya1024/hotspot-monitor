package com.codeya.hotspot.monitor.plugin.model;

import com.google.gson.annotations.SerializedName;

/** 慢 SQL / 慢下游 HTTP 聚合条目 */
public class SqlStat {

    public String label;
    public long count;
    @SerializedName("totalNanos")
    public long totalNanos;
    @SerializedName("maxNanos")
    public long maxNanos;
    @SerializedName("lastAtMs")
    public long lastAtMs;
    /** 最近一次触发时的业务调用来源 */
    @SerializedName("lastCaller")
    public String lastCaller;
}
