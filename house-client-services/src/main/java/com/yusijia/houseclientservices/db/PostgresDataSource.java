package com.yusijia.houseclientservices.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 一个可复用的 PostgreSQL 连接池（不含任何业务 DDL 与 SQL）。
 * <p>
 * 为什么单独抽出来：记忆层的摘要库、后续 RAG 的 pgvector 库是两个不同的 database，
 * 各自需要一条独立的池子。如果池子长在 {@code PostgresChatDigestStore} 里，
 * 加 RAG 时要么复制一份 Hikari 配置，要么把向量检索塞进摘要类——前者重复，后者臃肿。
 * 这里只负责"连上 + 给 JdbcTemplate + 能跑 DDL + 能关"，
 * 建表语句归各自的 store，谁用谁 {@link #execute(String)}。
 * <p>
 * <b>不要把它注册成 {@code DataSource} Bean</b>：Spring Boot 的
 * {@code DataSourceAutoConfiguration} 带 {@code @ConditionalOnMissingBean}，
 * 出现第二个 DataSource Bean 时业务库（MySQL）的连接池会直接失效。
 * <p>
 * 初始化失败只标记 {@link #isAvailable()} = false，不抛异常：
 * 存储层不可用时业务要能继续跑，不能因为一个旁路库连不上就起不来。
 */
public class PostgresDataSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresDataSource.class);

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String poolName;
    private volatile boolean available;

    public PostgresDataSource(String poolName, String url, String username, String password, int poolSize) {
        this.poolName = poolName;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        // 旁路库：池子小、失败快速返回，不要拖住用户回答
        config.setMaximumPoolSize(Math.max(poolSize, 1));
        config.setMinimumIdle(0);
        config.setConnectionTimeout(2000);
        config.setInitializationFailTimeout(-1);
        config.setPoolName(poolName);
        this.dataSource = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(dataSource);
        this.available = true;
    }

    /** 各 store 执行自己的建表语句；失败只降级不抛，避免"表建不出来 = 服务起不来" */
    public void execute(String ddl) {
        if (!available) {
            return;
        }
        try {
            jdbc.execute(ddl);
        } catch (RuntimeException e) {
            this.available = false;
            log.warn("PostgreSQL 存储不可用({})，相关能力将降级: {}", poolName, e.getMessage());
        }
    }

    /** 供各 store 执行自己的 SQL；使用前先判断 {@link #isAvailable()} */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public boolean isAvailable() {
        return available;
    }

    public String poolName() {
        return poolName;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
