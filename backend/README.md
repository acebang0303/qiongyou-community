# qiongyou-backend — 琼游100天交流分享社区

一个从"能跑通"演进到"接近生产"的 Spring Boot 后端：覆盖 Redis 缓存与一致性、Lua 原子幂等、
RabbitMQ 异步削峰、Feed 流与热榜、Elasticsearch 搜索、限流与降级，并补齐认证、事务、
可观测性、测试与容器化。

> 各阶段的改动设计、踩坑点与验证记录见 [CHANGELOG.md](CHANGELOG.md)；
> 生产化待办与完成状态见 [PRODUCTION-TODO.md](PRODUCTION-TODO.md)。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 框架 | Spring Boot 2.7.18 / Spring 5.3 / Java 8 |
| 持久化 | MySQL 8 + MyBatis-Plus 3.5.5 |
| 缓存 | Redis 7（Lettuce） |
| 消息 | RabbitMQ 3.12（topic exchange + 死信队列） |
| 搜索 | Elasticsearch 8.8.2 + analysis-ik 中文分词 |
| 安全 | JJWT 0.11.5（无状态认证）+ spring-security-crypto（BCrypt） |
| 文档/监控 | springdoc-openapi 1.7 + Spring Boot Actuator |
| 测试 | JUnit 5 + Testcontainers 1.19 |

## 架构

```
                    ┌──────────────┐
   HTTP /api/** ───▶ │ AuthInterceptor │ 解析 JWT → UserContext（匿名放行）      ─┐
                    └──────────────┘                                          │
                    ┌──────────────┐                                          │ 拦截器链
                    │ RateLimitInterceptor │ 固定窗口限流（按 userId / IP）      ─┘
                    └──────────────┘
                            │
                    ┌───────▼────────┐
                    │  Controller    │
                    └───────┬────────┘
                            │
                    ┌───────▼────────────────────────────────────┐
                    │  Service                                   │
                    │   ├─ 读：Cache Aside（note/user/comment）  │
                    │   ├─ 写：Lua 原子幂等（like/favorite/share）│
                    │   └─ 事务提交后再发 MQ（TransactionHelper） │
                    └───┬─────────────┬──────────────┬───────────┘
                        │             │              │
                 ┌──────▼───┐  ┌──────▼─────┐  ┌─────▼──────┐
                 │  MySQL   │  │   Redis    │  │ RabbitMQ   │
                 │ 最终一致 │  │ 缓存/计数/ │  │ 削峰 + DLQ │
                 │  的落库  │  │ Feed/热榜  │  └─────┬──────┘
                 └──────────┘  └────────────┘        │
                        ▲                            ▼
                        └────── Consumer ◀───── like/favorite/share/comment/note.es
                                                        │
                                                  ┌─────▼──────┐
                                                  │    ES      │
                                                  └────────────┘
        定时任务：对账（Redis↔MySQL 回填）、热榜重算（时间衰减）
```

## 技术演进线

| 阶段 | 解决的问题 | 关键手段 |
|---|---|---|
| 缓存层 | 热点笔记读压 MySQL | Cache Aside + TTL |
| 高频写 | 一次点赞 4 条 SQL | Redis Set 关系 + 计数器 |
| 幂等 | "判断+计数"竞态 | **Lua 脚本**合并为原子操作 |
| 缓存三兄弟 | 穿透/击穿/雪崩 | 空对象缓存 + 分布式锁互斥重建 + 随机 TTL |
| 削峰 | 洪峰直击数据库 | RabbitMQ 异步落库 + 消费端幂等 |
| 读扩散 | 关注页/热榜实时聚合慢 | ZSet Feed 收件箱 + 热榜 |
| 搜索与保护 | LIKE 全表扫、无自保护 | ES 倒排索引 + 固定窗口限流 + 降级兜底 |
| **生产化** | 认证/事务/一致性/可观测/测试/部署 | JWT、BCrypt、MQ 可靠性三件套、对账、IK、游标分页、Actuator、Testcontainers、Docker |

## 关键设计决策（面试可讲点）

- **幂等**：Redis Lua 把「判断+写入+计数」合并成一次原子执行，MySQL 唯一索引作兜底；
  不用 `MULTI`（无法读后分支判断）、不用 `synchronized`（集群失效）。
- **最终一致**：Redis 先应答 → MQ → 消费者落库；配 `publisher confirm` + `returns` + 死信队列，
  再加**对账定时任务**以 MySQL 为准回填 Redis（只增不删，避免误删在途数据）。
- **事务与 MQ 解耦**：不在事务内直接发消息（回滚撤不回消息），改用 `afterCommit` 回调。
- **Feed 推拉结合**：普通作者推给粉丝收件箱；粉丝数超阈值的作者只写自己的发件箱，
  粉丝读关注页时归并「收件箱 + 所关注大V发件箱」。收件箱按条数裁剪防膨胀。
- **热榜**：`score = 基础分 × 0.5^(龄期/半衰期)`，定时重算，避免早期爆款永久霸榜。
- **搜索**：IK 分词（建索引 `ik_max_word` / 搜索 `ik_smart`）+ `operator:and`（防单字误召回）
  + `search_after` 游标分页（根除深分页）。
- **降级**：ES 异常回退 MySQL；Redis 异常回退数据库；限流组件异常 fail-open。

## 快速开始

### 方式一：Docker（全部容器化）

```bash
# 只起中间件（开发时后端在 IDE 里跑）
docker compose up -d

# 连后端一起起（后端镜像含应用，ES 镜像已内置 IK 分词）
docker compose --profile app up -d
```

后端 API：<http://localhost:8080>　Swagger UI：<http://localhost:8080/swagger-ui/index.html>

### 方式二：本地跑后端

```bash
docker compose up -d          # 只起中间件
./ci.sh --skip-tests          # 编译打包
mvn spring-boot:run           # 默认 dev profile，连 3307/6380/5672/9200
```

环境相关配置见 `application.yml` 的 `${ENV:默认值}` 占位符；
`SPRING_PROFILES_ACTIVE=prod` 切换生产配置（关 SQL 打印、关接口文档、health 精简）。

### 校验

```bash
./ci.sh              # 编译 + 打包 + Testcontainers 集成测试（需 Docker）
./ci.sh --skip-tests # 只编译打包
```

### 测试账号

密码统一 `123456`（库中以 BCrypt 存储）：`xiaohong`、`sanya_walker`、`beach_girl` …

## 目录结构

```
src/main/java/com/qiongyou/
  auth/       认证拦截器（JWT → UserContext）
  common/     RedisKeys / Result / JwtUtil / UserContext / TransactionHelper / RedisLock
  config/     Redis / RabbitMQ(拓扑+DLX+发布确认) / Web(MVC+拦截器) / 密码 / OpenAPI
  consumer/   like / favorite / share / comment-notify / note-es 消费者
  controller/ REST 接口
  mapper/     MyBatis-Plus Mapper
  ratelimit/  固定窗口限流拦截器
  service/    业务 + 定时任务（ReconcileTask / HotRankTask）+ ES 服务与启动 Runner
  vo/ dto/ entity/
src/main/resources/   application.yml + application-{dev,prod}.yml
src/test/java/com/qiongyou/ Testcontainers 集成测试
```

## 已知限制

- **关注（follow）仍走 MySQL 同步写**，是唯一没 Redis 化的互动链路（低频操作，可接受）。
- **热榜衰减后，老内容分数趋近 0**，此时实时 `ZINCRBY` 的跳变会被放大（≤10 分钟内被重算拉回）。
- **健康检查把 ES 计入整体状态**：ES 挂掉会让 overall 变 DOWN，而应用其实可降级运行。
- **未接 CI 平台**：仓库在 Gitee，提供可复用的 `ci.sh`。
- **未引入 DB 迁移工具**：`init.sql` 为唯一 schema 真源（理由见 CHANGELOG）。

## 安全说明（重要）

本仓库里出现的所有口令与密钥**都是本地演示环境的默认值，不是真实凭据**：

| 项 | 默认值 | 说明 |
|---|---|---|
| 演示账号密码 | `123456` | `init.sql` 的种子数据（库中以 BCrypt 存储），仅为让 `docker compose up` 后能直接登录体验 |
| MySQL | `root / 123456` | 仅监听本地 `3307`（Docker 映射），由 `docker-compose.yml` 在本地创建 |
| RabbitMQ | `guest / guest` | RabbitMQ 自带默认账号，仅本地使用 |
| JWT 密钥 | yml 中的默认串 | 仅本地开发用；**任何真实部署都必须用环境变量覆盖** |

**部署到任何非本地环境前，必须通过环境变量覆盖**：`DB_PASSWORD`、`RABBITMQ_PASSWORD`、`JWT_SECRET` 等
（见 [application.yml](src/main/resources/application.yml) 的 `${ENV:默认值}` 占位符）。
生产 profile（`SPRING_PROFILES_ACTIVE=prod`）已默认关闭 SQL 打印与接口文档。

> 早期学习阶段参考的培训资料版权归原作者所有，未随本仓库公开。

