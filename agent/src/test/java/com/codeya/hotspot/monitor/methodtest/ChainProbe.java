package com.codeya.hotspot.monitor.methodtest;

import com.codeya.hotspot.monitor.agent.core.MethodTracker;

/**
 * 调用链测试探针：deeper 在插桩方法栈内捕获当前调用来源。
 * 位于 hotspot.packages 包内，会被方法变换器插桩进 MethodTracker 栈。
 */
public class ChainProbe {

    public static final ChainProbe INSTANCE = new ChainProbe();
    public static volatile String captured;

    public void run() throws Exception {
        deeper();
    }

    public void deeper() throws Exception {
        captured = MethodTracker.currentChain(5);
        Thread.sleep(2);
    }
}
