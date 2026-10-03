package com.codeya.hotspot.monitor.agent;

import com.codeya.hotspot.monitor.agent.core.SpanStore;
import com.codeya.hotspot.monitor.agent.model.ChildSpan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JDBC 插桩验证：真实 H2 驱动 + PreparedStatement 执行路径 */
class JdbcTest {

    @BeforeAll
    static void setup() throws Exception {
        AgentTestSupport.install();
        Class.forName("org.h2.Driver");
    }

    @BeforeEach
    void clean() {
        SpanStore.INSTANCE.clear();
    }

    @Test
    void recordsPreparedStatementSql() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:jdbctest;DB_CLOSE_DELAY=-1")) {
            try (PreparedStatement ps = c.prepareStatement("select 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // 消费结果集
                    }
                }
            }
        }

        ChildSpan span = TestUtil.waitForSpan("sql", "select 1");
        assertNotNull(span, "应捕获到 SQL span");
        assertTrue(span.nanos >= 0);
    }

    @Test
    void recordsExecuteWithSqlArgument() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:jdbctest2;DB_CLOSE_DELAY=-1")) {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("select 2");
            }
        }

        ChildSpan span = TestUtil.waitForSpan("sql", "select 2");
        assertNotNull(span, "应捕获到 execute(String) 的 SQL span");
        assertTrue(span.nanos >= 0);
    }
}
