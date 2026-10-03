package com.codeya.hotspot.monitor.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.sql.DataSource;

/**
 * 端到端演示服务（Spring Boot 3 / jakarta 栈）：
 *   /hello        快速接口
 *   /sql          一条慢 SQL（H2 SLEEP 250ms）
 *   /sql-loop     20 条小 SQL（聚合 SQL 耗时）
 *   /slow         业务逻辑卡点（Thread.sleep，非 SQL 耗时）
 *   /downstream   下游 HTTP：RestTemplate + OkHttp 打自己
 *   /error        抛异常（错误请求）
 */
@SpringBootApplication
@RestController
public class DemoApplication {

    private final JdbcTemplate jdbc;
    private final RestTemplate rest;
    private final OkHttpClient ok;
    private final org.apache.http.impl.client.CloseableHttpClient apache;
    private final org.springframework.web.reactive.function.client.WebClient wc;

    public DemoApplication(JdbcTemplate jdbc, RestTemplate rest, OkHttpClient ok,
                           org.apache.http.impl.client.CloseableHttpClient apache,
                           org.springframework.web.reactive.function.client.WebClient wc) {
        this.jdbc = jdbc;
        this.rest = rest;
        this.ok = ok;
        this.apache = apache;
        this.wc = wc;
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    /** 数据源/客户端等 @Bean 独立放到嵌套 @Configuration，避免与组件本身的构造注入形成循环创建 */
    @org.springframework.context.annotation.Configuration
    static class Cfg {

        @Bean
        public DataSource dataSource() {
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            return ds;
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        public RestTemplate restTemplate() {
            return new RestTemplate();
        }

        @Bean
        public OkHttpClient okHttpClient() {
            return new OkHttpClient();
        }

        @Bean
        public org.apache.http.impl.client.CloseableHttpClient apacheHttpClient() {
            return org.apache.http.impl.client.HttpClients.createDefault();
        }

        @Bean
        public org.springframework.web.reactive.function.client.WebClient webClient() {
            return org.springframework.web.reactive.function.client.WebClient.create();
        }
    }

    @GetMapping("/hello")
    public String hello() {
        return "hello";
    }

    @GetMapping("/sql")
    public String sql() {
        // 建一次 100 万行内存表；GROUP BY 全表排序 ~300ms+，稳定超过默认 200ms 慢 SQL 阈值
        jdbc.execute("CREATE TABLE IF NOT EXISTS big AS SELECT X AS ID, 'data_' || X AS VAL FROM SYSTEM_RANGE(1, 1000000) R(X)");
        jdbc.queryForList("SELECT COUNT(*) FROM (SELECT VAL FROM big GROUP BY VAL)");
        return "sql";
    }

    @GetMapping("/sql-loop")
    public String sqlLoop() {
        for (int i = 0; i < 20; i++) {
            jdbc.queryForList("SELECT 1");
        }
        return "sql-loop";
    }

    @GetMapping("/slow")
    public String slow() throws InterruptedException {
        Thread.sleep(400);
        return "slow";
    }

    @GetMapping("/downstream")
    public String downstream() throws Exception {
        String base = "http://127.0.0.1:" + System.getProperty("server.port", "18080");
        // 下游打 /slow（400ms），让 RestTemplate/OkHttp 子调用真实超过默认 300ms 阈值
        String a = rest.getForObject(base + "/slow", String.class);
        Request req = new Request.Builder().url(base + "/slow").get().build();
        String b;
        try (Response resp = ok.newCall(req).execute()) {
            b = resp.body() == null ? "?" : resp.body().string();
        }
        return a + b;
    }

    @GetMapping("/apache")
    public String apache() throws Exception {
        String base = "http://127.0.0.1:" + System.getProperty("server.port", "18080");
        try (org.apache.http.client.methods.CloseableHttpResponse resp =
                     apache.execute(new org.apache.http.client.methods.HttpGet(base + "/slow"))) {
            return org.apache.http.util.EntityUtils.toString(resp.getEntity());
        }
    }

    @GetMapping("/webclient")
    public String webclient() {
        String base = "http://127.0.0.1:" + System.getProperty("server.port", "18080");
        return wc.get().uri(base + "/slow").retrieve().bodyToMono(String.class).block();
    }

    @GetMapping("/error")
    public String error() {
        throw new IllegalStateException("demo error");
    }
}
