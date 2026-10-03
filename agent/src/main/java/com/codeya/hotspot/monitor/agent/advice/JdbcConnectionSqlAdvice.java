package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.SqlRegistry;
import net.bytebuddy.asm.Advice;

/**
 * Connection.prepareStatement(String) / prepareCall(String) 返回时，
 * 把“语句对象 -> SQL 文本”登记到弱引用表，供 execute 阶段取回。
 */
public class JdbcConnectionSqlAdvice {

    @Advice.OnMethodExit
    static void record(@Advice.Argument(0) String sql, @Advice.Return Object statement) {
        try {
            SqlRegistry.register(statement, sql);
        } catch (Throwable ignore) {
        }
    }
}
