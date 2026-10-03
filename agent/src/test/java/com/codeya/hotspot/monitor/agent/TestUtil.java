package com.codeya.hotspot.monitor.agent;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.model.ChildSpan;
import com.codeya.hotspot.monitor.agent.model.RequestSpan;

/** 轮询等待监控数据出现（插桩是异步触发，需短轮询）。 */
public final class TestUtil {

    private TestUtil() {
    }

    public static RequestSpan waitForRequest(String path) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            for (RequestSpan r : SpanStore.INSTANCE.recentRequests()) {
                if (path.equals(r.path)) {
                    return r;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    public static ChildSpan waitForSpan(String kind, String labelPrefix) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            for (RequestSpan r : SpanStore.INSTANCE.recentRequests()) {
                for (ChildSpan c : r.spans()) {
                    if (kind.equals(c.kind) && c.label.startsWith(labelPrefix)) {
                        return c;
                    }
                }
            }
            for (ChildSpan c : SpanStore.INSTANCE.recentOrphans()) {
                if (kind.equals(c.kind) && c.label.startsWith(labelPrefix)) {
                    return c;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }
}
