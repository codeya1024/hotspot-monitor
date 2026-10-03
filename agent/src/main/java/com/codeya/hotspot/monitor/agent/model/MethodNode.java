package com.codeya.hotspot.monitor.agent.model;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 请求内方法调用树节点（XRebel 式调用栈聚合）：
 * 一个方法名在其父方法下聚合为一个节点（同父同名多次调用合并计数），
 * children 保存下级方法调用。selfNanos = 自身耗时（总耗时 - 子调用耗时）。
 */
public final class MethodNode {

    public final String name;
    public long calls;
    public long selfNanos;
    public long totalNanos;
    public long maxNanos;
    public final Map<String, MethodNode> children = new ConcurrentHashMap<String, MethodNode>();
    /** 方法名呈 JavaBean 访问器形态（setXxx/getXxx）；配合"单次 <1ms 且无子调用"判据用于修剪纯 setter/getter 噪音 */
    public volatile boolean beanShape;
    /** 该方法执行期间（调用栈上）发生过 SQL：含 SQL 的方法永不因噪音规则被忽略 */
    public volatile boolean hasSql;
    /** 直接在该方法内执行的 SQL（挂载到业务方法/Mapper，展开方法即可看到 SQL 与耗时） */
    public final List<ChildSpan> childSqls = new CopyOnWriteArrayList<ChildSpan>();

    public MethodNode(String name) {
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
}
