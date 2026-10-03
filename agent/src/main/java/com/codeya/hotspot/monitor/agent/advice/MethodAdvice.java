package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.MethodTracker;
import net.bytebuddy.asm.Advice;

/**
 * 方法级计时（hotspot.packages 配置的包内所有非构造/非抽象方法）。
 * 仅当线程处于请求上下文时记录；不在请求上下文时 enter 返回 null，exit 空转（一次判空）。
 * 注意：不使用 skipOn —— 该项目已验证 skipOn 与返回值搭配会改变目标方法行为（方法体被跳过）。
 */
public class MethodAdvice {

    @Advice.OnMethodEnter
    public static int enter(@Advice.Origin("#t.#m#s") String origin) {
        return MethodTracker.enter(origin == null ? "?" : origin, true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter int depth) {
        MethodTracker.exit(depth);
    }
}
