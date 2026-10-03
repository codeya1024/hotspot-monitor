package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import net.bytebuddy.asm.Advice;

/**
 * Statement.execute(String) / executeQuery(String) / executeUpdate(String) / executeLargeUpdate(String)，
 * SQL 直接取参数。
 */
public class JdbcStatementSqlAdvice {

    @Advice.OnMethodEnter
    static long enter(@Advice.Argument(0) String sql) {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Argument(0) String sql, @Advice.Enter long start, @Advice.Thrown Throwable t) {
        try {
            SpanStore.INSTANCE.recordChild("sql", truncate(sql), start, t);
        } catch (Throwable ignore) {
        }
    }

    public static String truncate(String sql) {
        if (sql == null) {
            return "?";
        }
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > 8000 ? s.substring(0, 8000) + "..." : s;
    }
}
