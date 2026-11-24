# house-client-services

Spring Boot 服务（Spring AI 1.1.2 + spring-ai-alibaba 1.1.2.x），端口 `8085`。
房产客服智能体：技能由 `resources/skills/<name>/SKILL.md` 驱动，三层记忆（Redis 工作记忆 + PG 摘要 + Redis 会话状态）。

## 启动与验证

### 启动命令

Maven 跑在 Java 8 上，`spring-boot:run` 会因 `RunMojo` class version 61 失败，**必须用 `java -cp` 方式启动**。

```powershell
cd D:\ideaSpace\study\spring-ai-test

# 改代码或资源后必须先编译（target\classes 才是运行时的 classes 目录）
.\mvnw.cmd -o -pl house-client-services clean compile

# 首次启动需要先生成 classpath（生成 house-client-services\cp.txt，用完删掉）
.\mvnw.cmd -o -pl house-client-services dependency:build-classpath "-Dmdep.outputFile=cp.txt"
```

```powershell
cd D:\ideaSpace\study\spring-ai-test\house-client-services
$cp = "target\classes;" + (Get-Content cp.txt -Raw).Trim()
& "D:\work\Java\jdk21\bin\java.exe" -Dfile.encoding=UTF-8 -cp $cp `
    com.yusijia.houseclientservices.HouseClientServicesApplication *> ..\run.log
```

启动就绪判据（`run.log` 中出现）：`Tomcat started on port 8085` / `Started HouseClientServicesApplication`。

### 依赖服务

| 依赖 | 地址 | 凭据 | 说明 |
|---|---|---|---|
| Redis（工作记忆 + 会话状态） | `localhost:6379` db `0` | 无密码 | 容器 `agent-redis`（`redis:8-alpine` + AOF） |
| PostgreSQL（对话摘要，独立库） | `localhost:5432/agent_memory` | `postgres` / `PostgreSQL` | 容器 `pgvector-db` |
| MySQL（业务库） | `localhost:3306/springai` | `root` / `mysql` | 脚本 `src/main/resources/db/migration/V*.sql` |

**端口冲突处理**：本机 `127.0.0.1:6379` 平时还跑着公司的 `redis-server`（需密码 `Bitservice@2025`，
版本 3.2，不支持多字段 HSET）。跑本服务前**先停公司 redis**，让容器 `agent-redis` 占住 6379；
需要时可反过来停容器、起公司 redis。若确实要并存，把容器改映射到 6380 并设 `$env:AGENT_REDIS_PORT="6380"`。
不要复用公司的 `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` 变量，记忆层用 `AGENT_REDIS_HOST` / `AGENT_REDIS_PORT` / `AGENT_REDIS_DB`。

启动/重启依赖服务：

```powershell
docker start pgvector-db agent-redis
# 容器不存在时重建
docker rm -f agent-redis; docker run -d --name agent-redis -p 6379:6379 redis:8-alpine redis-server --appendonly yes
```

启动日志关键行：

```
Redis 记忆可用: PONG
对话摘要库已就绪: pool=chat-digest-pg
对话摘要模型: qwen-turbo
对话记忆: Redis 工作记忆(...) + PG 摘要，压缩器=LlmChatMemoryCompressor
```

旁路存储不可用只降级不报错：Redis 挂了退化为进程内窗口，PG 挂了只停掉摘要持久化。

### 必需环境变量（用户级）

`ALI_BAILIAN_KEY`（DashScope）、`BOCHA_KEY`（联网检索）、`BAIDU_DITU_KEY`（百度地图 MCP）。
注意：不要复用公司 Redis 的 `REDIS_PWD`，记忆层用独立的 `AGENT_REDIS_PWD`。

### 测试用例

接口：`POST http://127.0.0.1:8085/api/agent/chat`，`Content-Type: application/json`，
响应 `text/event-stream`（`data:` 逐块）。Body：`{"conversationId":"<id>","message":"<文本>"}`。

#### 用例1: 纯寒暄（技能选择器应返回空 → 只发 COMMON）
- 请求：`{"conversationId":"verify-greet","message":"你好"}`
- 预期：200 流式返回；日志 `skills=[], tools=[]`；prompt tokens 约 2000 级（不再 6000+）

#### 用例2: 业务问题命中技能
- 请求：`{"conversationId":"verify-biz","message":"我想给1幢101办认购"}`
- 预期：200；日志 `skills=[subscribe]`，`tools` 含 `createSubscription` 等；PG `chat_digest` 可无写入

#### 用例3: 会话状态与工作记忆落 Redis
- 检查：`redis-cli keys "chat:*"`、`redis-cli llen "chat:verify-biz:msg"`、`redis-cli hgetall "session:verify-biz"`
- 预期：每轮写入 user + assistant 两条；会话状态 Hash 在工具写事实后出现 `currentHouse` 等字段

#### 用例4: 记忆压缩
- 造数据：往 `chat:{ <cid> }:msg` 灌 24 条以上（合计 >6000 字符，Java `String.length()` 计）
- 触发：发一句"好的"
- 预期日志：`记忆已压缩: conversationId=..., 压缩 N 条, 保留 20 条, 摘要 K 字`
- 预期副作用：Redis `chat:{cid}:digest` 有值、LIST 被 `LTRIM` 到 20+；PG `chat_digest` 新增一行
- 失败路径（例如摘要模型 429 限流）应只降级为截断：`记忆压缩失败，已退回截断`，对话不受影响

> 摘要模型默认 `qwen-turbo`，免费额度打满会 `HTTP 429 Throttling.RateQuota`。
> 这时压缩恒走降级分支，验证成功路径可临时 `$env:AGENT_DIGEST_MODEL="qwen-plus"` 再启动。


### 停止

```powershell
# 按端口找 PID
netstat -ano | findstr :8085
Stop-Process -Id <PID>
```

## 注意

- **增量编译会假通过**：必须 `clean compile`，否则 `target\classes` 里的旧 class 会掩盖缺 import。
- `MemoryConfig` 不要注册第二个 `DataSource` Bean：会顶掉 Spring Boot 的 MySQL 自动装配；PG 池用 `db/PostgresDataSource`。
- 每次改 `application.properties` / `resources/` 都要重新 `compile`，否则 `target\classes` 里是旧资源。
- 冒烟结束后清理：`cp.txt`、Redis `chat:verify-*` / `session:verify-*`、PG 里 `verify-*` 摘要行。
- **不要用 PowerShell `Set-Content` / `Get-Content -Raw` 改 Java 源文件**：默认按 GBK 读 UTF-8，
  会把中文写成乱码且不可逆（`。` 的尾字节还会吞掉换行）。改代码一律用 edit/write 工具；
  已经写坏了就 `git checkout -- <file>` 还原后重做。
- 端到端造出的认购单会污染 `SubscriptionFlowTest` / `SubscriptionDataScopeTest`（它们依赖 V3 种子态）。
  冒烟后按 `biz_no` 删掉 `subscription` 及 `subscription_person/house/org` 明细，再跑 `clean test`。
- **页面用 `127.0.0.1:5173` 打开时所有 `/api` 请求会 403 `Invalid CORS request`**：`localhost` 与
  `127.0.0.1` 是不同 origin，`WebConfig` 的来源白名单必须两个都写（现已默认两个，可用
  `agent.cors.allowed-origins` 覆盖）。这个 403 由 Spring 的 CORS 校验直接返回，
  **后端日志里没有任何记录**，只能从浏览器 Network 面板或 CDP 看到，排查时别在服务端日志里白找。
- 前端登录是 P0-1 假登录：页面右上角输入 `customer1`（客户）/ `agent1`（坐席）登录，
  token 存 `localStorage['agent.token']`，两个业务请求都要带 `Authorization: Bearer`；
  未登录时列表页显示"请先登录"、聊天会提示先登录。
- Redis 没起时 `/api/auth/login` 会 500（`RedisSessionStore.put` 连接被拒），先 `docker start agent-redis`。
