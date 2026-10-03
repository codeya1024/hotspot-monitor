package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.SqlRegistry;
import net.bytebuddy.asm.Advice;

/**
 * Connection.createStatement() 返回时登记占位 SQL。
 */
public class JdbcConnectionNoSqlAdvice {

    @Advice.OnMethodExit
    static void record(@Advice.Return Object statement) {
        try {
            SqlRegistry.register(statement, "(createStatement)");
        } catch (Throwable ignore) {
        }
    }
}
