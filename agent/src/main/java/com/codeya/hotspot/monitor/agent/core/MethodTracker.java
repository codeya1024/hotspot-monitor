package com.codeya.hotspot.monitor.agent.core;

import com.codeya.hotspot.monitor.agent.model.MethodNode;
import com.codeya.hotspot.monitor.agent.model.RequestSpan;

/**
 * 请求内方法计时：仅当线程正处于请求上下文中时，维护一个方法调用栈，
 * 并按调用关系聚合为一棵方法调用树（XRebel 式：方法 → 下级方法 → 每层耗时）。
 * 不在请求上下文时 enter 返回 null，advice 整体跳过，开销极低。
 * 同一父方法下的同名方法（循环/多次调用）合并为一个节点并累计次数。
 */
public final class MethodTracker {

    /**
     * 方法调用栈：线程局部数组池，槽位按深度复用（enter/exit 不分配任何对象）。
     * 高频方法（如响应写入 ×900万次/请求）若每次 enter 都 new Frame，会给请求线程
     * 带来数千万次分配和 GC 压力——XRebel 用字节码内嵌计时规避的正是这一点。
     * 同一线程的 enter/exit 严格 LIFO（onThrowable 保证异常路径也执行 exit），
     * 因此按深度索引数组槽位是安全的；clearStack 在请求结束时兜底重置深度。
     */
    private static final int STACK_INIT = 64;
    private static final ThreadLocal<Frame[]> FRAMES = new ThreadLocal<Frame[]>() {
        @Override
        protected Frame[] initialValue() {
            return new Frame[STACK_INIT];
        }
    };
    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    /** 方法调用栈帧（栈数组槽位复用，不在 enter/exit 路径上分配） */
    public static final class Frame {
        Frame parent;
        MethodNode node;
        long start;      // nanoTime
        long childSelf;

        void reset(Frame parent, MethodNode node, long start) {
            this.parent = parent;
            this.node = node;
            this.start = start;
            this.childSelf = 0L;
        }
    }

    private MethodTracker() {
    }

    /** 纯访问器噪音判据：单次最大耗时 < 1ms 视为纯 setter/getter（MenuNode.setId ×万次、单次 0.05ms） */
    private static final long NOISE_MAX_NS = 1_000_000L;

    /** 进入方法；不在请求上下文中返回 -1 */
    public static int enter(String name) {
        return enter(name, true);
    }

    /**
     * 进入方法。trimNoise=true 时对该方法做"纯访问器噪音"修剪：
     * 方法名呈 setXxx/getXxx 形态 + 单次最大耗时 < 1ms + 无子调用 → 退出时从树中摘除。
     * 真业务方法（即使叫 getPortalIdsByUserIdAndTenantId）若耗时 >=1ms 或有下级调用，不会被误杀。
     * MapperProxy 节点传 false（接口名.getXxx 永远保留——它代表真实 SQL 调用）。
     * 返回栈深度（advice 用 int 携带，exit 时按深度取槽位）；不在请求上下文返回 -1。
     */
    public static int enter(String name, boolean trimNoise) {
        RequestSpan req = RequestContext.current();
        if (req == null) {
            return -1;
        }
        Frame[] frames = FRAMES.get();
        int[] depth = DEPTH.get();
        int d = depth[0];
        Frame parent = d == 0 ? null : frames[d - 1];
        MethodNode parentNode = parent != null ? parent.node : req.methodRoot;
        MethodNode node = parentNode.children.get(name);
        if (node == null) {
            node = new MethodNode(name);
            node.beanShape = trimNoise && isBeanAccessor(name);
            MethodNode existing = parentNode.children.putIfAbsent(name, node);
            if (existing != null) {
                node = existing;
            }
        }
        if (d >= frames.length) {
            Frame[] grown = new Frame[frames.length * 2];
            System.arraycopy(frames, 0, grown, 0, frames.length);
            frames = grown;
            FRAMES.set(grown);
        }
        Frame f = frames[d];
        if (f == null) {
            f = new Frame();
            frames[d] = f;
        }
        f.reset(parent, node, System.nanoTime());
        depth[0] = d + 1;
        return d;
    }

    /** 栈顶帧（advice 内部/测试用；无栈返回 null） */
    private static Frame topFrame() {
        int[] depth = DEPTH.get();
        int d = depth[0];
        if (d == 0) {
            return null;
        }
        return FRAMES.get()[d - 1];
    }

    /** 退出方法：自身耗时 = 本次总耗时 - 子调用耗时；累加给树节点并累计父节点子耗时 */
    public static void exit(int depth) {
        if (depth < 0) {
            return;
        }
        Frame[] frames = FRAMES.get();
        int[] dep = DEPTH.get();
        if (depth != dep[0] - 1) {
            // 深度不匹配（异常路径漏弹或错序）：无法安全按槽位结算，整体重置防御
            clearStack();
            return;
        }
        Frame f = frames[depth];
        dep[0] = depth;
        long total = System.nanoTime() - f.start;
        long self = Math.max(0L, total - f.childSelf);
        f.node.add(self, total);
        Frame parent = f.parent;
        if (parent != null) {
            parent.childSelf += total;
        }
        frames[depth] = null;
        // 纯访问器噪音修剪：快速无子调用的 set/get 直接摘出树（父的 childSelf 已累加，父自身耗时语义不变）
        if (f.node.beanShape && f.node.children.isEmpty() && f.node.maxNanos < NOISE_MAX_NS && !f.node.hasSql) {
            if (parent != null) {
                parent.node.children.remove(f.node.name, f.node);
            } else {
                RequestSpan req = RequestContext.current();
                if (req != null) {
                    req.methodRoot.children.remove(f.node.name, f.node);
                }
            }
        }
    }

    /**
     * 请求结束时清空本线程的方法调用栈（防御性兜底）。
     * 正常路径下 exit 链会把栈弹空；异常路径若出现未配对的 enter，
     * 残留栈会把下一个请求的方法挂到旧请求树上，这里在请求结束处统一清掉。
     */
    public static void clearStack() {
        int[] depth = DEPTH.get();
        Frame[] frames = FRAMES.get();
        for (int i = 0; i < depth[0]; i++) {
            frames[i] = null;
        }
        depth[0] = 0;
    }

    /**
     * SQL 记录时标记当前调用栈上的所有方法"含 SQL 调用"。
     * 用于噪音过滤：单次 < 阈值但执行过 SQL 的方法必须保留（SQL 的调用来源不能丢）。
     */
    public static void markHasSql() {
        Frame f = topFrame();
        while (f != null) {
            f.node.hasSql = true;
            f = f.parent;
        }
    }

    /**
     * 把 SQL 挂到"业务方法"节点（从栈顶向上跳过框架/拦截器/连接池帧，
     * 挂到第一个业务方法——Mapper/Service/Controller；如 queryMenusAndDirsByPortals）。
     * 这样方法树展开时可直接看到该方法内执行的 SQL 与耗时。
     * 栈全为框架帧时挂到栈顶（至少有归属），空栈不挂（孤 SQL 仍进 spans/orphan）。
     */
    public static void attachSql(com.codeya.hotspot.monitor.agent.model.ChildSpan c) {
        Frame f = topFrame();
        while (f != null && isFrameworkFrame(f.node.name)) {
            f = f.parent;
        }
        if (f == null) {
            f = topFrame(); // 全框架栈：兜底挂栈顶
        }
        if (f != null) {
            f.node.childSqls.add(c);
        }
    }

    /** 框架/拦截器/连接池帧特征：SQL 不挂到这些方法下（挂到其下的业务方法） */
    private static boolean isFrameworkFrame(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        String lower = name.toLowerCase();
        return lower.contains("interceptor")
                || lower.contains("filter")
                || lower.contains("druid")
                || lower.contains("litchi")
                || lower.contains("proxy")
                || lower.contains("transaction")
                || lower.contains("threadlocalmap")
                || lower.contains("collectslowsql");
    }

    /**
     * 从插桩维护的方法调用栈取调用来源（XRebel 同款思路：请求上下文内就地维护执行栈，
     * SQL/HTTP 发生时栈顶即"正在执行它的方法"，天然不含代理/框架/连接池噪音帧——
     * 只有 hotspot.packages 内的业务方法才会进栈）。
     * 从栈顶（离 SQL/HTTP 最近的调用者）向栈底取最多 max 个，
     * 形如 "SlowService.child ← SlowService.doSlow"；栈为空返回 null。
     */
    public static String currentChain(int max) {
        Frame f = topFrame();
        if (f == null) {
            return null;
        }
        StringBuilder sb = null;
        int n = 0;
        while (f != null && n < max) {
            String name = f.node.name;
            String simple = simplify(name);
            if (sb == null) {
                sb = new StringBuilder(simple);
            } else {
                sb.append(" ← ").append(simple);
            }
            f = f.parent;
            n++;
        }
        return sb == null ? null : sb.toString();
    }

    /**
     * 纯 JavaBean 访问器判断：方法简单名匹配 setXxx/getXxx（set/get + 大写字母）。
     * 这类方法在 MyBatis 结果集映射时可能被调用上万次（如 MenuNode.setId ×13072），
     * 自身耗时几乎为 0，纯属树渲染噪音，直接不记录。
     * 注意：方法名现在带参数签名（setValue(java.lang.String)），必须先截掉签名部分
     * 再取简单名，否则 lastIndexOf('.') 会取到参数类型包名里的点。
     */
    private static boolean isBeanAccessor(String name) {
        String method = simpleMethodName(name);
        if (method == null || method.length() < 4) {
            return false;
        }
        char c = method.charAt(3);
        return (method.startsWith("set") || method.startsWith("get")) && c >= 'A' && c <= 'Z';
    }

    /** 截掉参数签名后取纯方法名：com.x.Service.loadConfig(java.lang.String,int) → loadConfig */
    private static String simpleMethodName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        int paren = name.indexOf('(');
        String head = paren >= 0 ? name.substring(0, paren) : name;
        int m = head.lastIndexOf('.');
        return m >= 0 ? head.substring(m + 1) : head;
    }

    /** 截掉参数签名后取"类名.方法名"最后两段：com.x.Service.loadConfig(java.lang.String,int) → Service.loadConfig */
    private static String simpleNameOf(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        int paren = name.indexOf('(');
        String head = paren >= 0 ? name.substring(0, paren) : name;
        int m = head.lastIndexOf('.');
        if (m < 0) {
            return head;
        }
        int c = head.lastIndexOf('.', m - 1);
        return c >= 0 ? head.substring(c + 1) : head;
    }

    /** 全限定名（含签名）→ 简单名 "MenuServiceImpl.query"；签名里的点不影响（先截括号） */
    private static String simplify(String name) {
        String simple = simpleNameOf(name);
        return simple == null ? "?" : simple;
    }
}
