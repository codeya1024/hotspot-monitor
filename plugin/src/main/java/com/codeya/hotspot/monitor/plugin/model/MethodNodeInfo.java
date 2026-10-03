package com.codeya.hotspot.monitor.plugin.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** 方法调用树节点（来自 agent 的 methodTree） */
public class MethodNodeInfo {

    public String name;
    public long calls;
    @SerializedName("selfNanos")
    public long selfNanos;
    @SerializedName("totalNanos")
    public long totalNanos;
    @SerializedName("maxNanos")
    public long maxNanos;
    /** 该方法内（或其调用链）是否执行过 SQL——含 SQL 的方法永不折叠 */
    public boolean hasSql;
    public List<MethodNodeInfo> children;
    /** 直接在该方法内执行的 SQL（agent 挂载到业务方法/Mapper 节点） */
    public List<SqlRefInfo> sqls;

    public double selfMs() {
        return selfNanos / 1_000_000.0;
    }

    public double totalMs() {
        return totalNanos / 1_000_000.0;
    }
}
