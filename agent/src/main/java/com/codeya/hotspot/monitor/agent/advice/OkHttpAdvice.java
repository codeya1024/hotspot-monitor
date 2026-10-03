package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import net.bytebuddy.asm.Advice;

/**
 * OkHttp 同步调用插桩：okhttp3.internal.connection.RealCall.execute()（OkHttp 4.x 路径）。
 * <p>
 * 注意：@Advice.This 参数必须用 Object 而非 RealCall 类型——若在 advice 方法签名中直接引用
 * 被变换类自身，Byte Buddy 解析参数类型时会触发对目标类的嵌套加载（此时该类正在被 define），
 * 导致 LinkageError: duplicate class definition。方法体内强转则安全（运行期解析）。
 */
public class OkHttpAdvice {

    @Advice.OnMethodEnter
    static long enter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.This Object self,
                     @Advice.Enter long start,
                     @Advice.Thrown Throwable t) {
        try {
            String label = "?";
            if (self instanceof okhttp3.internal.connection.RealCall) {
                okhttp3.internal.connection.RealCall call = (okhttp3.internal.connection.RealCall) self;
                okhttp3.Request req = call.request();
                if (req != null && req.url() != null) {
                    String m = req.method();
                    label = (m == null ? "?" : m) + " " + req.url();
                }
            }
            SpanStore.INSTANCE.recordChild("http", truncate(label), start, t);
        } catch (Throwable ignore) {
        }
    }

    public static String truncate(String s) {
        return s.length() > 8000 ? s.substring(0, 8000) + "..." : s;
    }
}
