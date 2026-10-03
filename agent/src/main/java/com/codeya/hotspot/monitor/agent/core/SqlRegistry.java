package com.codeya.hotspot.monitor.agent.core;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * PreparedStatement 实例 -> SQL 文本 的弱引用登记表。
 * 插桩在 Connection.prepareStatement(String) 返回时登记，Statement.execute*() 时取回，
 * 从而在 execute 阶段也能拿到 SQL。弱键避免语句对象泄漏。
 */
public final class SqlRegistry {

    private static final Map<Object, String> STATEMENT_SQL =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());

    private SqlRegistry() {
    }

    public static void register(Object statement, String sql) {
        if (statement != null && sql != null) {
            STATEMENT_SQL.put(statement, sql);
        }
    }

    public static String lookup(Object statement) {
        return statement == null ? null : STATEMENT_SQL.get(statement);
    }
}
