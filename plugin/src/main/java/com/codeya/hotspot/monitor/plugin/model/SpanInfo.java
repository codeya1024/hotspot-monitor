package com.codeya.hotspot.monitor.plugin.model;

import com.google.gson.annotations.SerializedName;

/** 请求内部的一个耗时片段（SQL / 下游 HTTP） */
public class SpanInfo {

    public String kind;
    public String label;
    public long nanos;
    @SerializedName("offsetNanos")
    public long offsetNanos;
    public boolean ok;
    /** 业务调用来源（"Mapper.selectXxx(34) ← ServiceImpl.find(120)"） */
    public String caller;
    /** 发生时刻（epoch ms） */
    @SerializedName("atMs")
    public long atMs;

    public double ms() {
        return nanos / 1_000_000.0;
    }
}
