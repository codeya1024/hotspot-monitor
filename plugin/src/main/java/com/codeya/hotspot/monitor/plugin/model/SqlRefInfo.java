package com.codeya.hotspot.monitor.plugin.model;

/** 挂载在方法树节点下的 SQL（展开方法即可看到该方法内执行的 SQL 与耗时） */
public class SqlRefInfo {

    public String label;
    public long nanos;
    public boolean ok;

    public double ms() {
        return nanos / 1_000_000.0;
    }
}
