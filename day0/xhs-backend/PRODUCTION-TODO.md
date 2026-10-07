# xhs-backend 生产化 TODO 清单

> 目标：把 Day2–Day8 的实训成果补齐成一个"能上生产、面试讲得出来"的项目。
> 图例：**P0** 阻断/红线 ｜ **P1** 手册留白的生产项 ｜ **P2** 工程化 ｜ **P3** 性能安全细节
> 规模估算：S ≤ 半天 ｜ M ≈ 1 天 ｜ L ≥ 2 天
> 每个任务的改动设计 / 踩坑点 / 验证记录见 [CHANGELOG.md](CHANGELOG.md)

---

## P0 — 阻断级（不做就是坏的 / 面试一问就穿）

- [x] **P0-1 修复 `init.sql` 与线上库结构漂移** ｜ S ｜ ✅ 2026-10-06
  - `t_note_share` 表存在于当前库（手动建过），但没写进 `day0/sql/init.sql`；换机器 `docker compose up` 会缺表，分享落库直接报错。
  - 同一脚本还缺 `t_comment (note_id, id)` 联合索引（Day2 选做挑战文档建议，深分页/排序稳定性需要）。
  - 动手：把线上库 `SHOW CREATE TABLE` 的结果回填进 `init.sql`，并补 `ALTER TABLE t_comment ADD INDEX idx_note_id (note_id, id)`。
  - 验收：全新环境从 `init.sql` 建库后，分享一次能看到 `t_note_share` 落库。
  - **完成记录（2026-10-06）**
    - 改动文件：`day0/sql/init.sql`
      - 新增 `t_note_share` 表：列注释 + 表注释 `'分享表'` + `uk_user_note(user_id,note_id)` + `idx_note(note_id)`，置于收藏表之后；原「评论表/关注表」编号顺延为 6 / 7
      - `t_comment` 新增 `KEY idx_note_id (note_id, id)`
    - 线上库同步执行（`xhs-mysql` 容器）
      - `ALTER TABLE t_note MODIFY COLUMN share_count INT DEFAULT 0 COMMENT '分享数'` —— 修复原乱码注释 `'åˆ†äº«æ•°'`
      - `ALTER TABLE t_note_share MODIFY COLUMN note_id/user_id 补注释, ADD INDEX idx_note(note_id), COMMENT='分享表'`
      - `ALTER TABLE t_comment ADD INDEX idx_note_id (note_id, id)`
    - 验证：`information_schema.statistics` 全库索引与 `init.sql` 声明逐项一致；`share_count` 注释已恢复 `分享数`
    - 遗留（可选项）：`t_comment.idx_note` 被 `idx_note_id` 前缀覆盖属冗余索引，本次按「只增不删」保留，如追求干净可后续 `DROP INDEX idx_note`（需两边同步）

- [x] **P0-2 引入认证：`X-User-Id` 改为服务端签发** ｜ M ｜ ✅ 2026-10-06
  - 现状：所有 Controller 直接读 `@RequestHeader("X-User-Id")`（如 `InteractController`、`NoteController`、`UserController`），客户端想传谁传谁 → 可冒充任意用户。
  - 方案：**JWT 无状态 + 硬切换**（选型对比见 [CHANGELOG.md](CHANGELOG.md#p0-2-引入认证x-user-id--jwt2026-10-06)）
  - 动手点：`pom.xml`、`application.yml`、新增 `common/JwtUtil`、`common/UserContext`、`auth/AuthInterceptor`、`vo/LoginVO`；改 `config/WebConfig`、`service/UserService`、全部 `controller/*.java`；前端 `utils/user.js`、`api/index.js`、`views/Login.vue`
  - **完成记录**：拦截器语义为「可选认证」——匿名放行、无效 token 直接 HTTP 401；全项目已无任何地方读取 `X-User-Id`；登录接口返回 `{token, user}`
  - 验收：不带/伪造 token 的受保护接口返回 401，合法 token 正常；公开读接口不受影响
  - **注意（运维）**：改动需**重启后端**才生效；本地 8080 上的旧实例仍在跑旧代码

- [x] **P0-3 密码 BCrypt 加密** ｜ S ｜ ✅ 2026-10-06
  - 现状：`UserService.login` 明文比对（`user.getPassword().equals(password)`）。
  - 方案：**BCrypt（spring-security-crypto）+ 存量密码批量迁移**（`VARCHAR(50)` → `VARCHAR(100)`）
  - 动手点：`pom.xml`、新增 `config/PasswordConfig`、`service/UserService`、`day0/sql/init.sql`（列定义 + 8 条种子密码）、线上库 `ALTER` + `UPDATE`
  - **完成记录**：8 个用户密码已迁移为 BCrypt(`123456`)，登录仍用 123456；列注释改为「密码（BCrypt 哈希）」
  - 验收：库中无明文密码；正确密码 200 / 错误密码 401 / 不存在用户 401（无 NPE）

- [x] **P0-4 关键写链路加 `@Transactional`** ｜ S ｜ ✅ 2026-10-06
  - 全项目 0 处事务注解。`CommentService.add`（插评论 + 更新 `comment_count` + 发 MQ）、`NoteService.publish`（插笔记 + 推 Feed + 发 MQ）中途失败会留脏数据。
  - 方案：**提交后发送（`TransactionSynchronizationManager`）** + 事务覆盖「业务写 + 消费者」（选型见 [CHANGELOG.md](CHANGELOG.md#p0-4-关键写链路加事务--mq-与事务解耦2026-10-06)）
  - 动手点：新增 `common/TransactionHelper`；改 `service/NoteService`、`service/CommentService`、`consumer/{Like,Favorite,Share}Consumer`
  - **完成记录**：`publish`/`add` 加 `@Transactional` 并把 MQ 发送 + Feed/热度等副作用移入 `afterCommit`；三个落库消费者加 `@Transactional`
  - 验收：发布 200 且落库 + ES 收到；评论后明细数与 `comment_count` 原子一致；空标题 400 且无残留行
  - 遗留：提交成功与 afterCommit 之间崩溃会丢副作用，靠 P1-7 对账兜底

---

## P1 — 手册留白的生产项

### 缓存一致性
- [x] **P1-1 补 Day2 课堂实战：评论列表缓存** ｜ S ｜ ✅ 2026-10-06
  - `RedisKeys.commentList(noteId)` 已定义但**全项目零引用**；`CommentService.listByNote` 直查库。
  - 方案：**游标分页（新→旧）+ 只缓存首页**（`comment:list:{noteId}:1`，TTL 5 分钟，空列表 1 分钟）
  - 动手点：`common/RedisKeys`、`mapper/CommentMapper`、`service/CommentService`、`controller/CommentController`；前端 `api/index.js`、`views/NoteDetail.vue`
  - **完成记录**：游标查询 `id < lastId ORDER BY id DESC`；仅首页+标准页大小走缓存；发评论在 `afterCommit` 删首页缓存；前端加「加载更多」
  - 验收：首页 DESC；缓存 TTL≈300s；游标逐页无重叠；发评论后缓存失效且新评论置顶
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-1-评论列表缓存游标分页--只缓存首页2026-10-06)

- [x] **P1-2 消除 `note:{id}` 缓存计数陈旧** ｜ S ｜ ✅ 2026-10-06
  - `NoteService.mergeCounts` 只合并 like/favorite 计数，`commentCount`、`shareCount` 仍是缓存里的旧值（最长陈旧 30 分钟）。
  - 方案：**shareCount 从 Redis 合并**（`share:count:{id}`）+ **commentCount 发评论后删 `note:{id}`**（评论无 Redis 计数器，同步写库）
  - 动手点：`service/NoteService.mergeCounts`、`service/CommentService.add`（`afterCommit`）
  - 验收：Redis `share:count:1=777` 时详情返回 777；发评论后 `note:1` 缓存失效且 `commentCount` 立即更新
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-2-消除-noteid-缓存计数陈旧2026-10-06)

- [x] **P1-3 关注/取关后失效 `user:{id}` 缓存** ｜ S ｜ ✅ 2026-10-07
  - `user:{id}` 缓存含 `followCount`/`fansCount`，关注后不清 → 主页数字陈旧。
  - 注意：关注是双向影响（我 +1、对方 fans +1），两个 key 都要清。
  - 动手点：`service/InteractService.follow/unfollow` 成功后 `evictUserCache(userId, targetUserId)`
  - 验收：预热两个 key → 关注 → 两个 key 都被删；`fansCount`/`followCount` 立即更新
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-3-关注取关后失效-userid-缓存2026-10-07)

- [ ] **P1-4 补 Day4 挑战：关注走 Redis Lua 幂等** ｜ M
  - `RedisKeys.follow()` / `fansCount()` 已定义但未使用；`InteractService.follow` 仍是 MySQL `selectCount` + `insert`。
  - 现状可接受（关注是低频操作），但补上后"互动全链路 Redis 化"的故事才完整。

### MQ 可靠性（Day6 三层防丢只做了两层）
- [x] **P1-5 开启 publisher confirm + returns** ｜ S ｜ ✅ 2026-10-07
  - `application.yml` 现只有 host/port：加 `publisher-confirm-type: correlated` + `publisher-returns: true` + `template.mandatory: true`，并写 `ConfirmCallback`/`ReturnsCallback` 记日志。
  - 动手点：`application.yml`、新增 `config/RabbitConfirmConfig`、发送侧补 `CorrelationData`
  - **完成记录**：回调注册在独立 `@Component`（构造器注入 RabbitTemplate，避免时序问题）；发送侧 5 处补可读 correlationId
  - 验收：真实删除 `note.es.#` 绑定后发布，日志打印 `【MQ return】...NO_ROUTE`，且 ES 未收到；绑定已恢复
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-5-开启-publisher-confirm--returns2026-10-07)

- [x] **P1-6 配置死信队列（DLX）** ｜ M ｜ ✅ 2026-10-07
  - 手册明说"生产标配但本课不实现"。给 `like/favorite/share/note.es` 各配 DLX + 重试次数上限，避免失败消息无限重投。
  - 尤其 `EsConsumer` 现在是 `throw e` 无限重投，Redis/ES 长期不可用会顶死队列。
  - 方案：**每源队列一个 DLQ + 本地重试 3 次耗尽后进死信**
  - 动手点：`config/RabbitConfig`（DLX/5 个 DLQ/绑定/队列参数/`MessageRecoverer`）、`application.yml`（`listener.simple.retry`）
  - **完成记录**：拓扑 `xhs.dlx` + `*.dlq`；重试 3 次（1s/2s 退避）后 reject → 进 `*.dlq`
  - ⚠️ **运维踩坑**：队列参数不可变，加 DLX 需**先删旧队列**；且必须**停掉旧实例**，否则旧实例连接恢复时会按旧参数复活旧队列（本次已因此踩坑）
  - 验收：投递坏消息 → `like.db.dlq=1` 且日志打印失败 body；正常消息不受影响
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-6-配置死信队列-dlx2026-10-07)

- [x] **P1-7 对账定时任务** ｜ M ｜ ✅ 2026-10-07
  - 手册点名：`SCARD like:{id}` vs `SELECT COUNT(*) FROM t_note_like`，不一致补写。这是"最终一致"故事的闭环，缺了它面试官会追问"Redis 丢了怎么办"。
  - 方案：**以 MySQL 为准、只增不删增量回填 Redis** + `@Scheduled` 每 10 分钟（首轮启动后 10s）
  - 动手点：`XhsApplication`（`@EnableScheduling`）、新增 `service/ReconcileTask`、`service/NoteService`（顺带修 bug）
  - **完成记录**：like/favorite/share 三类集合与计数按 MySQL 回填；采用「只增不删」避免误删在途数据
  - 🐞 **顺带修复真 bug**：`fillStatus`/`mergeCounts` 改用 `StringRedisTemplate`——原先读侧 JSON 序列化与写侧裸字符串不匹配，导致**"已点赞/已收藏"状态恒为 false**（Redis 为空时被掩盖）
  - 验收：首轮回填生效、第二轮幂等；用户 3 对 note 1 `liked=true`、用户 2 `false`
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-7-对账定时任务--顺带修复已点赞状态-bug2026-10-07)

- [ ] **P1-8 手动 ACK + 失败重试策略** ｜ M
  - 现在用默认 AUTO；精细控制需 `acknowledge-mode: manual` + `basicAck/basicNack`。

### Feed / 热榜（Day7 只做了推模式基线）
- [x] **P1-9 大 V 推拉结合** ｜ L ｜ ✅ 2026-10-07
  - `FeedService.pushNote` 无条件全量推给所有粉丝，千万粉大 V 会写爆。加粉丝数阈值，超阈值走拉模式，读时归并。
  - 方案：**作者发件箱 ZSet + 大V只写自己、粉丝读时拉取归并**
  - 动手点：`common/RedisKeys`、`application.yml`（阈值）、`mapper/FollowMapper`、`service/FeedService`
  - **完成记录**：`pushNote` 先写 `feed:outbox:{author}`，粉丝数超阈值则不再推；`feedIds` 合并「收件箱 + 所关注大V发件箱」按时间倒序分页
  - 验收：阈值=3 时 user2(6粉) 发布不推粉丝、走拉模式且关注页可见；user7(2粉) 正常推送到粉丝收件箱
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-9-大v推拉结合2026-10-07)

- [x] **P1-10 Feed 收件箱裁剪** ｜ S ｜ ✅ 2026-10-07
  - 生产应 `ZREMRANGEBYRANK feed:{id} 0 -101` 只保留最新 100 条，否则 ZSet 无限膨胀。
  - 方案：写入后按排名裁剪，保留最新 `xhs.feed.keep`（默认 100，可配）；收件箱与发件箱都裁
  - 动手点：`application.yml`、`service/FeedService`（`trim` + `pushNote`）
  - 验收：keep=2 时连发 4 篇，`ZCARD feed:2`/`feed:outbox:7` 均为 2，只留最新两条
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-10-feed-收件箱裁剪2026-10-07)

- [x] **P1-11 热度时间衰减 + P1-12 热榜定时重算**（合并实现）｜ M ｜ ✅ 2026-10-07
  - `HotService` 只增不减，早期爆款永久霸榜；且无定时重算修正误差。两者本质是同一个定时任务（重算时施加衰减），故合并。
  - 方案：**半衰期指数衰减** `score = base × 0.5^(龄期小时/半衰期)`，`@Scheduled` 每 10 分钟重写 `hot:notes`
  - 动手点：`application.yml`（`xhs.hot.half-life-hours`）、`service/HotService`（`setScore`）、新增 `service/HotRankTask`
  - 验收：日志打印重算条数；逐条比对 `ZSCORE` 与公式期望值 8/8 吻合
  - 已知局限：老内容衰减到 ~0 后，实时 `addHeat` 的跳变被放大（≤10 分钟内被重算拉回）
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-11--p1-12-热度时间衰减--热榜定时重算2026-10-07)

### 限流 / 搜索（Day8 粗粒度）
- [x] **P1-13 限流按用户维度** ｜ S ｜ ✅ 2026-10-07
  - `RateLimitInterceptor` 是全局窗口，防不住羊毛党。Key 改 `rate:limit:{user}:{接口}:{秒}`（Day8 选做挑战）。
  - 方案：**auth 拦截器提到限流之前**（否则拿不到 userId）+ Key 拼 `u{userId}`/`ip{IP}` + 阈值改单用户量级且 yml 可配
  - 动手点：`application.yml`、`config/WebConfig`（顺序）、`ratelimit/RateLimitInterceptor`
  - 验收：user1 并发 10 次搜索（阈值 3）= 3×200 + 7×429；user2 独立计桶全 200
  - 附带修复：pom 补 `project.build.sourceEncoding=UTF-8`（首次跑通 `mvn clean compile`）；修掉 javadoc 里 `**/` 提前终止注释的写法
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-13-限流改为按用户维度2026-10-07)

- [x] **P1-14 ES 接入 IK 中文分词** ｜ S ｜ ✅ 2026-10-07
  - 现在 `standard` 分词把"三亚"拆成"三""亚"。建索引用 `ik_max_word`、搜索用 `ik_smart`。
  - 完成：容器装 `analysis-ik 8.8.2` 并重启；mapping 改 IK；**附带修 `multi_match` 加 `operator:and`**（IK 未收录的词退化成单字时会误召回，实测「清补凉」3→2）
  - ⚠️ 插件在容器内、不在镜像里，`compose down/up` 会丢（建议并入 Dockerfile）
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-14-es-接入-ik-分词2026-10-07)

- [x] **P1-15 ES 存量数据回填任务** ｜ M ｜ ✅ 2026-10-07
  - 现在只有手工 `day8/notes-backfill.ndjson`。写一个 `_bulk` 回填的 Runner/接口，索引重建后可程序化恢复。
  - 完成：`EsBackfillRunner`（索引为空则 `_bulk` 全量回填）+ `EsService.count()/bulkIndex()`
  - 验收：删索引后重启自动重建并回填 20 篇，ES 与 MySQL 文档数一致
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-15-es-存量数据回填2026-10-07)

- [x] **P1-16 ES 深分页改 `search_after`** ｜ S ｜ ✅ 2026-10-07
  - 现在用 `from/size`，深分页性能差。
  - 完成：排序 `[{_score desc},{note_id asc}]` + `search_after`；API 改返回 `SearchPageVO{list,nextCursor}`；前端搜索页加「加载更多」
  - ⚠️ 重大踩坑：ES 8 禁止对 `_id` 排序，报错被 try/catch 静默降级到 MySQL，表面正常实为未走 ES → 加 `note_id` 数值字段做 tiebreaker
  - 验收：搜「三亚」size=2 逐页 `[16,3]→[1,6]→[17,8]→[]`，6 条无重复；游标由 ES 生成
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p1-16-es-深分页-fromsize--search_after2026-10-07)

---

## P2 — 工程化 / 可观测 / 测试

- [x] **P2-1 引入 Actuator** ｜ S ｜ ✅ 2026-10-07 — `spring-boot-starter-actuator`；只暴露 `health,info,metrics`；新增 `EsHealthIndicator`（ES 无内置指标）
  - 验收：`/actuator/health` 显示 db/redis/rabbit/es 均 UP；`/actuator/env` 404；停 ES 后 es 变 DOWN 并自动恢复
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p2-1-引入-actuator健康检查--指标2026-10-07)
- [x] **P2-2 单元 / 集成测试** ｜ L ｜ ✅ 2026-10-07 — Testcontainers（MySQL/Redis/RabbitMQ）singleton 容器 + `@SpringBootTest`；9 个用例全绿
  - 覆盖：Lua 幂等（含 100 并发）、缓存防穿透/空对象 TTL、对账回填与幂等；ES 指向死端口以覆盖降级路径
  - ⚠️ 关键坑：基类上用 `@Container` 会导致每个测试类重启容器、端口变化，而 Spring 上下文缓存仍指旧端口 → 必须用 singleton 容器模式
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p2-2-核心集成测试testcontainers2026-10-07)
- [x] **P2-3 API 文档** ｜ S ｜ ✅ 2026-10-07 — springdoc-openapi-ui 1.7.0（Boot 2.x 需 1.x）+ `OpenApiConfig`（含 bearerAuth 方案）；prod profile 关闭文档
  - 验收：dev `/v3/api-docs` 16 接口 + `/swagger-ui/index.html` 200；prod 两者均 404
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p2-3-api-文档springdoc--swagger-ui2026-10-07)
- [x] **P2-4 多环境配置** ｜ S ｜ ✅ 2026-10-07 — `application.yml` 只留公共项，环境相关（DB/Redis/MQ/JWT/ES/端口）走 `${ENV:默认值}`；拆出 `application-dev.yml`（打 SQL、debug、health 详细）与 `application-prod.yml`（关 SQL、info、health 精简）
  - 验收：`SPRING_PROFILES_ACTIVE=prod SERVER_PORT=8082` 启动 → prod 生效、端口被覆盖、零 SQL 打印；`mvn test` 仍 9/9
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p2-4-多环境配置2026-10-07)
- [x] **P2-5 后端 Dockerfile + compose 修复** ｜ S ｜ ✅ 2026-10-07
  - 新增多阶段 `Dockerfile`、`.dockerignore`、`es/Dockerfile`（固化 IK 插件）
  - 修复 compose：挂载 `init.sql`（此前注释说会自动建库但**根本没挂**→全新环境是空库）、ES 改 build、新增 `backend` 服务（`app` profile）
  - 验收：两镜像构建成功；`--profile app up -d` 后 5 容器运行、数据卷保留（20 篇）；新 ES 带 IK；容器化后端登录/搜索/health 均正常
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p2-5-后端-dockerfile--compose-修复2026-10-07)
- [x] **P2-6 CI** ｜ M ｜ ✅ 2026-10-07 — 仓库在 Gitee，不接具体平台，改为可复用的 `ci.sh`（clean verify + Docker 探测 + `--skip-tests`）
  - 验收：`./ci.sh` → 9/9 测试通过、BUILD SUCCESS
  - 详情见 [CHANGELOG.md](CHANGELOG.md#p2-6-ci-校验脚本cish2026-10-07)

- [x] **P2-7 清理 `pom.xml`** ｜ S ｜ ✅ 2026-10-07 — 删除重复的 `mysql:mysql-connector-java:5.1.32`（只留 Boot 管理的 `com.mysql:mysql-connector-j`）；更新 `<description>`
  - 验收：`mvn test` 9/9 通过

- [ ] **P2-8 数据库迁移工具** ｜ M ｜ ⏸ **暂缓（用户决定）** — 与「init.sql 单一真源」红线冲突，会产生双份 schema；当前 schema 变更频率低、无实际痛点。待 schema 频繁演进或多环境需各自演进时，按「Flyway 单一真源」一次性迁移。理由见 [CHANGELOG.md](CHANGELOG.md#p2-8-db-迁移工具flywayliquibase--暂缓2026-10-07)

---

## P3 — 性能 / 安全细节（能体现深度的加分项）

- [ ] **P3-1 消除 N+1 Redis 往返** ｜ M ｜ 🔴 **已实测确认：当前第一优先级的性能项**
  - 📌 2026-10-07 压测实测：`list` 场景优化后比基线慢 **+538%**（6.8→43.4ms），根因就是这里的 N+1（每篇 2 次 `SISMEMBER` + 3 次 `GET`）。
    详见 [docs/PERF.md](docs/PERF.md)。修法：改 pipeline / `MGET` / `SMISMEMBER` 批量一次往返。
  - `NoteService.fillStatus` 对列表每条笔记逐个 `isMember`；`mergeCounts` 逐个 `get`。列表 10 条 = 30+ 次往返。
  - 改 pipeline 批处理，或 `SMISMEMBER` / `MGET`。

- [ ] **P3-2 锁释放改 Lua 比较值再删** ｜ S
  - `NoteService`/`UserService` 直接 `delete(lockKey)`，存在误删他人锁隐患（手册 Day5 已点名）。

- [ ] **P3-3 收窄 Redis 反序列化** ｜ M
  - `RedisConfig` 用了 `activateDefaultTyping(LaissezFaireSubTypeValidator)`，已知反序列化 gadget 风险。收窄白名单或改显式类型 JSON。

- [ ] **P3-4 连接池调优** ｜ S — HikariCP / Lettuce 全用默认值，需按压测结果调参。
- [ ] **P3-5 抢锁失败改轮询重试** ｜ S — `Thread.sleep(50)` 阻塞请求线程，改为有限次轮询重读缓存。

---

## 面试叙事补强（非代码）

- [x] **N-1 写一份项目 README** ｜ M ｜ ✅ 2026-10-07 — 见 [README.md](README.md)：技术栈、架构图、技术演进线、关键设计决策（面试可讲点）、快速开始、已知限制
- [x] **N-2 整理压测数据** ｜ S ｜ ✅ 2026-10-07（已完成**优化前后对照实测**）— 见 [docs/PERF.md](docs/PERF.md)
  - 方法：`git worktree` 取基线 `593bffa`（Day1 全直查 MySQL）与当前 HEAD，两边都指向**独立压测库 `xhs_bench`**（3000 笔记/15 万点赞），同一个 JMeter 计划 `docs/bench/bench.jmx` 各压 1000 请求（零错误）
  - **结果（真实，且出乎意料）**：`detail` 6.5→9.9ms、`list` 6.8→43.4ms、`hot` 9.5→44.7ms、`search` 6.9→134.1ms、`like` 3.6→5.3ms —— **优化后读接口更慢**
  - **根因已定位**：读路径 **N+1 Redis 往返**（每篇笔记 2 次 `SISMEMBER` + 3 次 `GET`；10 篇列表 = 50 次往返）。证据：`detail`(1 篇) 只慢 3.4ms，`list`(10 篇) 慢 36.6ms → ≈3.7ms/篇，与「每篇 5 次往返」吻合
  - 🚫 **不要在简历上写"QPS 提升 N 倍"**——当前实测是优化后更慢。这份数据的价值是**实测出 P3-1（消除 N+1）确是当前第一优先级**
  - 脚本：`docs/bench/{bench.jmx,run-bench.sh,gen_bench.sql}`；压测库 `xhs_bench` 已保留，可复测
- [x] **N-3 准备高频追问的答案** ｜ S ｜ ✅ 2026-10-07 — 见 [docs/INTERVIEW-QA.md](docs/INTERVIEW-QA.md)：14 条问答（认证/一致性/MQ 可靠性/缓存三兄弟/幂等/事务与 MQ/Feed 推拉/热榜衰减/限流/ES/序列化 bug/降级掩盖故障/测试基建坑/密码/后续规划），每条都能指到具体文件

---

## 建议落地顺序（投入小、面试收益大）

1. P0-1 → P0-2 → P0-3 → P0-4（先把红线堵住）
2. P1-1、P1-2、P1-4、P1-5~P1-7（补全手册留白，故事闭环）
3. P2-1、P2-2、N-1~N-3（可验证 + 讲得出来）
4. 其余按精力推进
