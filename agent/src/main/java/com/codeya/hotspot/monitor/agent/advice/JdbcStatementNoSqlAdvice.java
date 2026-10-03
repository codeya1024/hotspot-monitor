package com.codeya.hotspot.monitor.agent.advice;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.core.SqlRegistry;
import net.bytebuddy.asm.Advice;

/**
 * Statement.execute*()（无 SQL 参数版本，如 execute() / executeQuery() / executeUpdate() / executeBatch()）。
 * SQL 文本从弱引用登记表取回（预编译语句时由 Connection.prepareStatement 登记）。
 */
public class JdbcStatementNoSqlAdvice {

    @Advice.OnMethodEnter
    static long enter(@Advice.This Object self) {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.This Object self, @Advice.Enter long start, @Advice.Thrown Throwable t) {
        try {
            String sql = SqlRegistry.lookup(self);
            if (sql == null) {
                sql = "(batch/no-sql)";
            }
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
