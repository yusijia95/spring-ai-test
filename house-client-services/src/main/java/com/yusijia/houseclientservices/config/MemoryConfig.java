package com.yusijia.houseclientservices.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yusijia.houseclientservices.agent.memory.AgentChatMemory;
import com.yusijia.houseclientservices.agent.memory.ChatDigestStore;
import com.yusijia.houseclientservices.agent.memory.ChatMemoryCompressor;
import com.yusijia.houseclientservices.agent.memory.ChatMemoryLimits;
import com.yusijia.houseclientservices.agent.memory.ChatMessageStore;
import com.yusijia.houseclientservices.agent.memory.SessionStateStore;
import com.yusijia.houseclientservices.agent.memory.UserProfileStore;
import com.yusijia.houseclientservices.agent.memory.llm.LlmChatMemoryCompressor;
import com.yusijia.houseclientservices.agent.memory.postgres.PostgresChatDigestStore;
import com.yusijia.houseclientservices.agent.memory.postgres.PostgresChatMessageStore;
import com.yusijia.houseclientservices.agent.memory.postgres.PostgresUserProfileStore;
import com.yusijia.houseclientservices.agent.memory.redis.ChatMessageCodec;
import com.yusijia.houseclientservices.agent.memory.redis.JedisRedisOperations;
import com.yusijia.houseclientservices.agent.memory.redis.RedisChatMemory;
import com.yusijia.houseclientservices.agent.memory.redis.RedisOperations;
import com.yusijia.houseclientservices.agent.memory.redis.RedisSessionStateStore;
import com.yusijia.houseclientservices.db.PostgresDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * 记忆层装配。
 * <p>
 * 这三样东西的边界必须分清，否则很容易做成"双真相"：
 * <ul>
 *   <li><b>工作记忆</b>：Redis LIST，最近一段对话，权威读源；</li>
 *   <li><b>情节记忆</b>：PostgreSQL 追加表，只存 LLM 摘要，不存原始消息；</li>
 *   <li><b>会话状态</b>：Redis Hash，当前楼盘/房号/认购单等业务事实，与对话窗口无关。</li>
 * </ul>
 * 契约在 {@code agent.memory} 包，实现在 {@code redis} / {@code postgres} / {@code llm} 子包。
 * <p>
 * 自定义 {@link ChatMemory} Bean 会顶掉 Spring AI 自动装配的
 * {@code MessageWindowChatMemory}（它是 {@code @ConditionalOnMissingBean}），
 * 因此原来写在 MySQL 里的 {@code SPRING_AI_CHAT_MEMORY} 不再写入。
 */
@Configuration
public class MemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfig.class);

    @Bean(destroyMethod = "close")
    public JedisPool agentJedisPool(
            @Value("${agent.memory.redis.host:localhost}") String host,
            @Value("${agent.memory.redis.port:6379}") int port,
            @Value("${agent.memory.redis.password:}") String password,
            @Value("${agent.memory.redis.database:0}") int database,
            @Value("${agent.memory.redis.timeout-ms:2000}") int timeoutMillis,
            @Value("${agent.memory.redis.pool-size:16}") int poolSize) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(poolSize);
        config.setMaxIdle(poolSize);
        config.setMinIdle(1);
        // 连接失效时（Redis 重启过）优先换一条新连接，而不是把异常抛给业务
        config.setTestOnBorrow(true);
        String pwd = password == null || password.isBlank() ? null : password;
        return new JedisPool(config, host, port, timeoutMillis, pwd, database);
    }

    @Bean
    public RedisOperations redisOperations(JedisPool agentJedisPool) {
        JedisRedisOperations ops = new JedisRedisOperations(agentJedisPool);
        try {
            log.info("Redis 记忆可用: {}", ops.ping() ? "PONG" : "无响应");
        } catch (RuntimeException e) {
            // 启动时不因 Redis 不可用而失败：记忆会降级为进程内窗口
            log.warn("Redis 不可用，记忆将降级为进程内窗口: {}", e.getMessage());
        }
        return ops;
    }

    @Bean
    public SessionStateStore sessionStateStore(
            RedisOperations redisOperations,
            @Value("${agent.session.ttl-seconds:86400}") long ttlSeconds) {
        return new RedisSessionStateStore(redisOperations, ttlSeconds);
    }

    /**
     * 摘要库连接池。故意不注册成 {@code DataSource} Bean，避免顶掉 MySQL 的自动装配。
     * <p>
     * 后续加 RAG 时，用同一个类再建一个指向 pgvector 库的实例即可，
     * 不需要改这里，也不需要往摘要存储里塞向量逻辑。
     */
    @Bean(destroyMethod = "close")
    public PostgresDataSource chatDigestDataSource(
            @Value("${agent.memory.pg.url:jdbc:postgresql://localhost:5432/agent_memory}") String url,
            @Value("${agent.memory.pg.username:postgres}") String username,
            @Value("${agent.memory.pg.password:postgres}") String password,
            @Value("${agent.memory.pg.pool-size:4}") int poolSize) {
        return new PostgresDataSource("chat-digest-pg", url, username, password, poolSize);
    }

    @Bean
    public ChatDigestStore chatDigestStore(PostgresDataSource chatDigestDataSource) {
        return new PostgresChatDigestStore(chatDigestDataSource);
    }

    /**
     * 原始消息持久化（工作记忆的权威副本）。
     * 和摘要共用同一个 PG 池：同一个库、同样的连接生命周期，将来迁库一起走。
     */
    @Bean
    public ChatMessageStore chatMessageStore(PostgresDataSource chatDigestDataSource) {
        return new PostgresChatMessageStore(chatDigestDataSource);
    }

    /**
     * L3 用户画像（语义记忆）：按 userId 持久化在 PG，跨会话共享。
     * 和摘要/原始消息共用同一个 PG 池。
     */
    @Bean
    public UserProfileStore userProfileStore(PostgresDataSource chatDigestDataSource) {
        return new PostgresUserProfileStore(chatDigestDataSource);
    }

    @Bean
    public ChatMessageCodec chatMessageCodec() {
        return new ChatMessageCodec();
    }

    /**
     * 摘要用便宜快的模型，只输出几百字。
     * 关闭时 {@link RedisChatMemory} 会退回纯截断。
     */
    @Bean
    public ChatMemoryCompressor chatMemoryCompressor(
            DashScopeChatModel dashScopeChatModel,
            @Value("${agent.memory.compress.enabled:true}") boolean enabled,
            @Value("${agent.memory.compress.model:qwen-turbo}") String model) {
        if (!enabled) {
            // 关闭压缩时保留一个"永远失败"的实现：RedisChatMemory 的降级路径就是截断，
            // 不必为 null 再做一套分支（Bean 返回 null 会让按类型注入直接失败）
            return (previousDigest, messages) -> null;
        }
        // 独立一个 ChatClient：摘要只需要便宜快的模型，和技能选择器分开配，互不影响
        log.info("对话摘要模型: {}", model);
        ChatClient digestClient = ChatClient.builder(dashScopeChatModel)
                .defaultOptions(DashScopeChatOptions.builder()
                        .model(model)
                        .temperature(0.0d)
                        .build())
                .build();
        return new LlmChatMemoryCompressor(digestClient, model);
    }

    @Bean
    public AgentChatMemory chatMemory(RedisOperations redisOperations,
                                      ChatMessageCodec chatMessageCodec,
                                      ChatDigestStore chatDigestStore,
                                      ChatMessageStore chatMessageStore,
                                      ChatMemoryCompressor compressor,
                                      @Value("${agent.memory.max-messages:80}") int maxMessages,
                                      @Value("${agent.memory.max-chars:20000}") int maxChars,
                                      @Value("${agent.memory.keep-recent:40}") int keepRecent,
                                      @Value("${agent.memory.ttl-seconds:86400}") long ttlSeconds,
                                      @Value("${agent.memory.compress.lock-ttl-ms:5000}") long lockTtlMillis) {
        log.info("对话记忆: Redis 工作记忆(maxMessages={}, maxChars={}, keepRecent={}) + PG 原始消息 + PG 摘要，压缩器={}",
                maxMessages, maxChars, keepRecent, compressor.getClass().getSimpleName());
        return new RedisChatMemory(redisOperations, chatMessageCodec, chatDigestStore, chatMessageStore,
                compressor, new ChatMemoryLimits(maxMessages, maxChars, keepRecent, ttlSeconds, lockTtlMillis));
    }
}
