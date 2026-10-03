package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.MethodTracker;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * MyBatis Mapper 代理插桩（Pinpoint 同款做法）：
 * Mapper 接口方法在 JDK 动态代理（org.apache.ibatis.binding.MapperProxy）上执行，
 * 代理类不在 hotspot.packages 域内，若不插桩，方法调用树里会丢失 "Mapper接口.方法名" 这一层。
 * 这里直接插桩 MapperProxy.invoke，以"接口全限定名.方法名"作为树节点，
 * 使调用链呈现：业务方法 → basicCenterUserMapper.getPortalIdsByUserIdAndTenantId → 拦截器 → JDBC。
 */
public class MapperProxyAdvice {

    @Advice.OnMethodEnter
    public static int enter(@Advice.Argument(1) Method method) {
        String name;
        try {
            // 带上参数类型（重载方法可区分）：basicCenterUserMapper.getPortalIdsByUserIdAndTenantId(long, String)
            StringBuilder sb = new StringBuilder(method.getDeclaringClass().getName())
                    .append('.').append(method.getName()).append('(');
            Class<?>[] ps = method.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(ps[i].getSimpleName());
            }
            sb.append(')');
            name = sb.toString();
        } catch (Throwable t) {
            name = "MapperProxy.invoke";
        }
        return MethodTracker.enter(name, false);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter int depth) {
        MethodTracker.exit(depth);
    }
}
