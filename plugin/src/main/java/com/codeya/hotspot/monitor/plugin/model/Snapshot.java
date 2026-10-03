package com.codeya.hotspot.monitor.plugin.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** /api/snapshot 的完整返回 */
public class Snapshot {

    public AgentInfo agent;
    public Thresholds thresholds;
    public List<RequestInfo> requests;
    public List<EndpointStat> endpoints;
    public List<SqlStat> slowSqls;
    public List<SqlStat> slowHttps;
    public List<SpanInfo> orphanSpans;

    public static class AgentInfo {
        public String pid;
        public String app;
        public String version;
        public long startedAt;
    }

    public static class Thresholds {
        @SerializedName("slowSqlMs")
        public long slowSqlMs;
        @SerializedName("slowHttpMs")
        public long slowHttpMs;
        /** 方法树噪音折叠阈值：插件把单次 < 该值且无 SQL 的高频小调用折叠为占位行 */
        @SerializedName("noiseThresholdMs")
        public long noiseThresholdMs;
    }
}
