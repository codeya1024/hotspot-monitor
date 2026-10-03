package com.codeya.hotspot.monitor.plugin.model;

import com.google.gson.annotations.SerializedName;

/** 方法时间线事件：进入时刻（相对请求起点）+ 总耗时 + 自身耗时 */
public class MethodEvent {

    public String name;
    @SerializedName("offsetNanos")
    public long offsetNanos;
    public long nanos;
    @SerializedName("selfNanos")
    public long selfNanos;

    public double ms() {
        return nanos / 1_000_000.0;
    }

    public double selfMs() {
        return selfNanos / 1_000_000.0;
    }

    public double offsetMs() {
        return offsetNanos / 1_000_000.0;
    }
}
