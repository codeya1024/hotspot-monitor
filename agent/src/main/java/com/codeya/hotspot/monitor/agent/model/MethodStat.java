package com.codeya.hotspot.monitor.agent.model;

/**
 * 一个方法在请求内的耗时聚合（按方法名聚合）。
 * selfNanos = 自身耗时（总耗时 - 内部子调用耗时），用于定位"方法本身慢"的卡点。
 */
public final class MethodStat {

    public final String name;
    private long calls;
    private long selfNanos;
    private long totalNanos;
    private long maxNanos;

    public MethodStat(String name) {
        this.name = name;
    }

    public synchronized void add(long self, long total) {
        calls++;
        selfNanos += self;
        totalNanos += total;
        if (total > maxNanos) {
            maxNanos = total;
        }
    }

    public synchronized long calls() {
        return calls;
    }

    public synchronized long selfNanos() {
        return selfNanos;
    }

    public synchronized long totalNanos() {
        return totalNanos;
    }

    public synchronized long maxNanos() {
        return maxNanos;
    }
}
