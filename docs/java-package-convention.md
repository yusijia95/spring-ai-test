# house-client-services 分包规范

> 适用：Spring Boot 3.x + Spring AI 1.1.x + MyBatis-Plus，多实例 + 负载均衡。
> 原则：**按技术层分包，层内按职责分接口/实现子包。**

## 包结构总览

```
com.yusijia.houseclientservices/
├── HouseClientServicesApplication.java    ← 启动类
├── agent/                                  ← 智能体核心（技能选择、提示词、工具注册）
│   ├── AgentPrompts.java                  ← COMMON 铁律提示词
│   ├── SkillSelector.java                 ← 技能选择器
│   ├── ToolRegistry.java                  ← 工具注册 + 写工具熔断
│   ├── RetrievalPlanner.java              ← 检索规划
│   ├── SkillCatalog.java                  ← SKILL.md 加载
│   └── ...
├── agent/memory/                           ← 记忆层（契约在根，实现在子包）
│   ├── AgentChatMemory.java              ← 契约（接口/抽象）
│   ├── ChatDigestStore.java              ← 契约
│   ├── ChatMemoryCompressor.java         ← 契约
│   ├── ChatMemoryLimits.java             ← 值对象
│   ├── SessionStateStore.java            ← 契约
│   ├── redis/                            ← Redis 实现
│   │   ├── RedisChatMemory.java
│   │   ├── RedisOperations.java          ← Redis 命令接口
│   │   ├── JedisRedisOperations.java     ← Jedis 实现
│   │   └── ChatMessageCodec.java         ← 编解码
│   ├── postgres/                         ← PostgreSQL 实现
│   │   └── PostgresChatDigestStore.java
│   └── llm/                              ← LLM 实现
│       └── LlmChatMemoryCompressor.java
├── config/                                 ← Spring 装配（@Configuration / @Bean）
│   ├── AiConfig.java                     ← ChatClient 装配
│   ├── MemoryConfig.java                 ← 记忆层装配
│   ├── MybatisPlusConfig.java            ← MapperScan
│   ├── SecurityConfig.java               ← 安全层装配
│   ├── WebConfig.java                    ← CORS
│   └── ...
├── controller/                             ← 控制器（HTTP 入口）
│   ├── AgentController.java
│   ├── AuthController.java
│   ├── MasterDataController.java
│   ├── SubscriptionController.java
│   └── dto/                              ← 请求/响应 DTO（record）
│       ├── ChatRequest.java
│       ├── LoginRequest.java
│       └── LoginResponse.java
├── domain/                                 ← 领域模型
│   ├── entity/                           ← 数据库实体（MyBatis-Plus @TableName）
│   │   ├── Person.java
│   │   ├── House.java
│   │   ├── Conversation.java
│   │   └── ...
│   └── view/                             ← 查询视图（不含敏感字段）
│       ├── HouseView.java
│       ├── PersonView.java
│       └── SubscriptionView.java
├── exception/                              ← 异常定义 + 全局异常处理
│   ├── BizException.java                 ← 业务校验异常
│   ├── GlobalExceptionHandler.java       ← @RestControllerAdvice
│   └── ...
├── mapper/                                 ← MyBatis-Plus Mapper 接口
│   ├── PersonMapper.java
│   ├── ConversationMapper.java
│   └── ...
├── observability/                          ← 可观测性
│   ├── TraceContext.java
│   └── TraceIdFilter.java
├── security/                               ← 安全机制（纯机制，不放业务服务）
│   ├── UserContext.java                  ← 值对象（record）
│   ├── PermissionPoints.java             ← 权限点常量
│   ├── Authorization.java                ← 角色×权限矩阵判定
│   ├── AccessDeniedException.java        ← 权限拒绝异常
│   ├── UserContextFilter.java            ← OncePerRequestFilter
│   ├── DemoAccounts.java                 ← 演示账号白名单
│   ├── SessionStore.java                 ← 会话令牌存储接口
│   └── redis/                            ← Redis 实现（与 agent/memory/redis/ 对齐）
│       └── RedisSessionStore.java
├── service/                                ← 业务服务（纯类，CGLIB 代理，无需接口）
│   ├── AuthService.java
│   ├── ConversationService.java
│   ├── MasterDataService.java
│   └── SubscriptionService.java
├── tool/                                   ← Spring AI @Tool 工具类
│   ├── SubscriptionTools.java
│   ├── ProjectTools.java
│   ├── WebSearchTools.java
│   ├── SessionStateTools.java
│   ├── BaiduMapToolGuard.java
│   └── ExternalContentGuard.java
└── db/                                     ← 数据库连接（非 Spring DataSource）
    └── PostgresDataSource.java           ← PG 连接池（不注册为 DataSource Bean）
```

## 分包规则

### 1. 按技术层分，不按业务域分

| 层 | 包 | 放什么 | 不放什么 |
|---|---|---|---|
| 控制器 | `controller/` | `@RestController`、DTO | 业务逻辑 |
| 服务 | `service/` | 业务服务（纯类） | 安全机制、Redis 实现 |
| 安全机制 | `security/` | 值对象、常量、判定、Filter、异常、接口 | 业务服务、Redis 实现 |
| 领域 | `domain/` | 实体、视图 | 业务逻辑 |
| 数据访问 | `mapper/` | MyBatis-Plus Mapper 接口 | SQL 实现（在 XML 或注解） |
| 配置 | `config/` | `@Configuration`、`@Bean` 装配 | 业务逻辑 |
| 智能体 | `agent/` | 技能选择、提示词、工具注册、记忆契约 | 业务服务 |
| 工具 | `tool/` | `@Tool` 标注的方法 | 业务逻辑 |
| 可观测 | `observability/` | TraceId、MDC、日志增强 | 业务逻辑 |
| 异常 | `exception/` | 异常类 + `@RestControllerAdvice` | 业务逻辑 |

### 2. 接口与实现分包（多实现时）

**规则：接口放父包根，实现放技术子包。**

```
agent/memory/              ← 契约（接口/抽象/值对象）
├── AgentChatMemory.java
├── redis/                 ← Redis 技术实现
├── postgres/              ← PostgreSQL 技术实现
└── llm/                   ← LLM 技术实现

security/                  ← 契约（接口/值对象/常量/判定）
├── SessionStore.java      ← 接口
└── redis/                 ← Redis 技术实现
    └── RedisSessionStore.java
```

**什么时候需要接口：**
- 有多个实现（Redis/PG/LLM 三种记忆存储）
- 需要测试时用内存实现替换（SessionStore 可以用 Map 实现做单测）
- 契约与实现解耦，将来换技术栈只改实现类

**什么时候不需要接口：**
- 只有一个实现且短期内不会换（service/ 下的业务服务）
- Spring Boot 默认 CGLIB 代理，`@Transactional` 标在类方法上完全有效

### 3. service 包：纯类，不强制接口

```java
@Service
public class SubscriptionService {

    @Transactional
    public Subscription create(...) { ... }
}
```

- Spring Boot 2.x 起默认 `spring.aop.proxy-target-class=true`（CGLIB 代理）
- `@Transactional` 标在类方法上有效，不需要接口
- 好处：少一层抽象，代码更简洁
- 限制：类和方法不能是 `final`（CGLIB 需要生成子类）

**例外：需要接口的场景**
- 有多个实现（策略模式）
- 需要测试 mock（但 Mockito 可以直接 mock 类，不强制）
- 跨模块契约（本服务是单体，暂不需要）

### 4. security 包：只放机制，不放业务

| 放 security/ | 放 service/ |
|---|---|
| `UserContext`（值对象） | `AuthService`（登录/登出业务） |
| `PermissionPoints`（常量） | `ConversationService`（会话归属业务） |
| `Authorization`（判定逻辑） | |
| `UserContextFilter`（Filter） | |
| `AccessDeniedException`（异常） | |
| `SessionStore`（接口） | |
| `DemoAccounts`（白名单） | |

**判断标准：** 如果这个类是"安全机制本身"（解析 token、判定权限、存会话），放 `security/`；
如果是"业务操作"（登录、登出、会话管理），放 `service/`。

### 5. 异常处理

- 异常类定义放 `exception/`（`BizException`、`AccessDeniedException`）
- `@RestControllerAdvice` 也放 `exception/`（`GlobalExceptionHandler`）——异常处理跟异常定义放一起
- 不放 `config/`：handler 不是装配，是异常处理的组成部分

### 6. DTO 放 controller/dto/

- 请求/响应 DTO 用 `record`（Java 17+）
- 不放 `domain/`：domain 放数据库实体，DTO 是 HTTP 层的传输对象
- 命名：`XxxRequest`、`XxxResponse`

### 7. 数据库迁移

- `resources/db/migration/V<N>__<name>.sql`
- Flyway 管理，版本号递增
- 主键：雪花 ID（应用层生成），不用 AUTO_INCREMENT
- 逻辑删除：`deleted_flag`（0=正常，删除时置为主键值）

### 8. 配置

- `application.properties` 集中管理
- 环境变量用 `${ENV_VAR:default}` 语法
- 敏感信息（API key、密码）不写死，用环境变量
- 安全相关配置前缀 `agent.security.*`

## 命名约定

| 类型 | 命名 | 示例 |
|------|------|------|
| 实体 | 名词（单数） | `Person`、`Conversation` |
| 视图 | 名词 + View | `HouseView`、`PersonView` |
| 服务 | 名词 + Service | `SubscriptionService` |
| Mapper | 实体名 + Mapper | `PersonMapper` |
| 控制器 | 名词 + Controller | `AuthController` |
| DTO | 名词 + Request/Response | `LoginRequest`、`ChatResponse` |
| 工具 | 名词 + Tools | `SubscriptionTools` |
| 异常 | 名词 + Exception | `BizException`、`AccessDeniedException` |
| 配置 | 名词 + Config | `AiConfig`、`SecurityConfig` |
| Filter | 名词 + Filter | `TraceIdFilter`、`UserContextFilter` |
| 权限点 | `域:资源:动作` | `chat:use`、`data:house:search` |
| 权限点常量 | 大写下划线 | `CHAT_USE`、`DATA_HOUSE_SEARCH` |

## 权限：接口级看权限点，数据级看范围

两个机制分工，不要混用：

| 机制 | 位置 | 回答的问题 | 拒绝方式 |
|------|------|------------|----------|
| 权限点 `PermissionPoints` + `Authorization` | controller / 接口层 | "这个角色能不能做这类操作" | `AccessDeniedException` → 403 |
| 数据范围 `DataScope` | `@Tool` 工具内 / service 内 | "能在这个人的数据上做吗" | 返回原因字符串 → 模型转述给客户 |

角色差异**优先用数据范围表达**，不要用"关掉权限点"来实现——否则自助客户会连自己的业务都办不了。

```
customer（自助客户）: 与坐席同样的权限点，但 personId 非空 → 只能操作本人数据
agent（客服坐席）   : personId 为 null → 可代任意客户操作
```

工具内取当前用户走 Spring AI 的 `ToolContext`：controller 用
`prompt().toolContext(Map.of(ToolExecutionContext.KEY, exec))` 显式下传本轮上下文，
`@Tool` 方法声明 `ToolContext` 参数后用 `ToolExecutionContext.of(toolContext)` 取。
**不要用 ThreadLocal**——控制器返回 `Flux<String>`，工具在响应式操作符之后/并行调度/虚拟线程上
执行时线程早就换了，而且失效方式是静默放行（fail-open）。
也不要从工具参数里接收角色或用户 ID（模型可以伪造）。

`DataScope` 一律 **fail-closed**：`user == null`（上下文缺失）按拒绝处理，绝不能当成"坐席，不限制"。
查询类先调 `queryViolation`，再调 `queryOwnerFilter`（后者对 null 直接抛异常，避免"返回 null = 不过滤"的越权）。

包装 `ToolCallback` 时必须重写 `call(String, ToolContext)` 并把上下文**转发**给内层：
`ToolCallback.call(String, ToolContext)` 是 default 方法，默认忽略上下文并转调 `call(String)`，
不重写就会丢上下文，带 `ToolContext` 参数的工具会被框架以"ToolContext is required"拒绝。

## 禁止事项

1. **不要**在 `security/` 放业务服务（`AuthService` 应该在 `service/`）
2. **不要**在 `service/` 放 Redis/PG 实现（应该在 `security/redis/` 或 `agent/memory/redis/`）
3. **不要**在 `config/` 放异常处理（应该在 `exception/`）
4. **不要**在 `domain/` 放 DTO（应该在 `controller/dto/`）
5. **不要**注册第二个 `DataSource` Bean（PG 池用 `db/PostgresDataSource`，不走 Spring 自动装配）
6. **不要**在 `@Tool` 方法里做权限校验（权限在 controller 层校验，tool 层只熔断）
7. **不要**在日志里打 PII（手机号、证件号、银行卡号）——用 `PiiMasker` 脱敏
8. **不要**把外部检索内容进 system prompt（应该作为独立 user 消息，`<external_data>` 包裹）
9. **不要**用 ThreadLocal 传请求级上下文（用户、会话、各类标记）——用 `ToolExecutionContext` + `ToolContext`
10. **不要**让安全校验在上下文缺失时放行（fail-open）——一律 fail-closed

## 测试

- 单元测试放 `src/test/java`，包结构与主代码对齐
- 纯逻辑测试（不依赖 Spring/Redis/DB）：直接 new 对象
- 集成测试（依赖 Spring 容器）：`@SpringBootTest`
- 命名：`XxxTest`

## 参考

- Spring Boot 3.x 官方文档：CGLIB 代理默认开启
- Alibaba Java 开发手册：分层架构、命名约定
- 本项目历史决策：见 `docs/enterprise-agent-roadmap.md`
