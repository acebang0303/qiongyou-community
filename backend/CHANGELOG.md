# CHANGELOG — 琼游100天交流分享社区 后端生产化改造

记录每个 P0/P1/P2/P3 任务的改动设计、踩坑点与验证方式。任务定义见 [PRODUCTION-TODO.md](PRODUCTION-TODO.md)。

---

## 仓库结构调整：目录平铺到顶层（2026-10-07）

### 问题
仓库里后端、前端、SQL 脚本分散在多层嵌套子目录，在独立项目里没有意义，
而且混着代码、脚本、压测产物，看起来像作业堆而非一个项目。

### 方案

| 原位置 | 新位置 |
|---|---|
| 后端工程嵌套目录 | `backend/` |
| 前端工程嵌套目录 | `frontend/` |
| SQL 脚本目录 | `sql/` |
| 限流压测与回填数据 | `benchmarks/` |

- 新增**顶层 `README.md`**：项目总览 + 目录结构 + 快速开始 + 「想看什么去哪看」索引
- 第三方参考资料（手册、参考实现、样例数据）统一收进 `_course-materials/` 并整目录忽略，
  不再散落在业务目录里
- 同步更新文档中的路径引用（`sql/`、`benchmarks/ratelimit/` 等）

### 踩坑点
- **必须用文件系统 `mv` 而不是 `git mv`**：`git mv` 只搬已跟踪文件，会把 `node_modules`、`target`、
  `.idea` 等未跟踪内容留在原地，新路径下的前端将失去依赖。
- **被忽略的参考资料会跟着目录一起搬**，而 `.gitignore` 里还是旧路径 → 它们会立刻变回「可被跟踪」。
  所以调整目录后**必须同步改 `.gitignore`**；这次干脆把所有课程资料收进单一目录统一忽略。
- **目录被占用导致 `mv` 失败**（`Device or resource busy` / `Permission denied`）：IDE 或当前工作目录
  持有句柄时无法重命名目录本身。绕法是改为「逐个子项搬运」，目录本身留空即可（空目录 git 不跟踪）。

### 验证（已实测）
- `./ci.sh` → 9/9 通过（`pom.xml` 依赖的 `../sql/init.sql`、`docker-compose.yml` 的 `../sql/init.sql`
  都是相对路径，移动后仍然成立）
- 顶层只剩 `backend/ frontend/ sql/ benchmarks/` + `.gitignore` + `README.md`

---

## 项目改名：xhs → 琼游100天交流分享社区（2026-10-07）

### 改动范围（文案 + 代码标识，**不动运行时资源**）

| 类别 | 改动 |
|---|---|
| Java 包名 | `com.xhs` → `com.qiongyou`（含 `src/main` 与 `src/test`，用 `git mv` 保住历史） |
| 启动类 | `XhsApplication` → `QiongyouApplication` |
| 配置命名空间 | `xhs.*` → `qiongyou.*`（`@Value("${qiongyou.jwt.secret}")` 等 22 处） |
| Maven | groupId `com.qiongyou`、artifactId `qiongyou-backend`、name、description |
| 应用名/信息 | `spring.application.name`、`info.app.*`、JWT 密钥默认值（yml 与 compose 同步改） |
| 接口文档 | Swagger 标题与描述 |
| 前端 | 页面标题、导航 logo、登录页标题、`package.json` 名称/描述、localStorage 键与自定义事件名（`qiongyou-user` / `qiongyou-token` / `qiongyou-user-changed`） |
| 文档 | README / PRODUCTION-TODO / docs 全部改为新名 |

### 刻意**不改**的（属运行时资源，改了会孤儿化线上状态）

- 容器名 `xhs-*`、数据库名 `xhs`、MQ 交换机 `xhs.exchange` / `xhs.dlx`、ES 索引 `xhs_notes`
- 顶层工程目录名（改动会波及 `node_modules` 等未跟踪文件与构建上下文）

### 验证（已实测）
- `./ci.sh` → **9/9 通过**（包名从 `com.xhs` 迁到 `com.qiongyou` 后测试类全部正常）
- 前端 `npm run build` 通过，产物 `dist/index.html` 标题为「琼游100天交流分享社区」
- 全仓（排除 node_modules/target/dist/.idea）已无 `com.xhs` / `XhsApplication` / `xhs-backend` / `红薯社区` / `仿小红书` 残留

### 注意
- 改了 `artifactId` → **jar 名变为 `qiongyou-backend-1.0.0.jar`**（`Dockerfile` 已同步），容器镜像需重新构建才会生效。
- JWT 密钥默认值变了 → 已签发的 token 全部失效，需重新登录。

---

## P3-2 / P3-3 / P3-4 / P3-5（2026-10-07）

### P3-2 分布式锁释放带归属校验 + P3-5 抢锁失败改轮询

**问题**：
- 锁释放是直接 `DEL`。锁有 TTL，业务执行超过 TTL 时会过期并被别人抢到，此时 `DEL` 会把**别人的锁**删掉，互斥当场失效。
- 没抢到锁时只 `Thread.sleep(50)` 一次就回源查库，等于把压力又打回 DB；`UserService` 更甚——它递归重入 `userInfo()`，在锁被长期持有时会**递归堆积**。

**方案**：抽出 `common/RedisLock`（`tryLock` 返回唯一 token；`unlock` 用 Lua 比对 token 再删），
两个服务（`NoteService` / `UserService`）统一改用；没抢到锁改为**有限次轮询**（5 × 20ms）等别人重建，
始终没有才兜底查库。`UserService` 顺带把 VO 组装抽成 `loadUserFromDb()`，消除递归。

**踩坑点（又一个序列化陷阱）**：token 与 Lua 都**必须走 `StringRedisTemplate`**。
若用 JSON 序列化的 `RedisTemplate` 写 token，存进去是带引号的 `"uuid"`，而 Lua 的 ARGV 是裸 `uuid`
→ 比对永远不相等 → **锁永远删不掉，只能干等 TTL 过期**（互斥范围被动扩大 10 秒）。

**验证（已实测）**：
- 重建后 `note:lock:1` = 0 —— 证明 token 与 Lua 的序列化成对正确（否则锁必残留）
- 预先塞入外部 token 占锁，再请求 → 外部锁**仍在且值未被改动**（我方 Lua 不删别人的锁）
- 锁被外部占着时 `/api/notes/2` 仍返回 200（轮询 + 兜底查库生效）

### P3-3 收窄 Redis 反序列化白名单

**问题**：`RedisConfig` 用 `LaissezFaireSubTypeValidator` 做 default typing —— **放行任意类型**。
一旦缓存数据被篡改/注入，反序列化时可被用来构造 gadget 链（RCE）。

**方案**：换成 `BasicPolymorphicTypeValidator` 白名单，只允许 `com.xhs.`（本项目 VO/实体）、
`java.util.`（集合）、`java.time.`（LocalDateTime）；**刻意不放行 `java.lang.`**（`Runtime` 是经典 gadget）。

**验证（已实测）**：`user:{id}`（UserVO）、`comment:list:{id}:1`（List<CommentVO>）、
**空列表标记**、笔记详情、搜索、热榜全部正常读回 —— 白名单没有误伤正常类型。
`./ci.sh` 9/9 通过。

### P3-4 连接池显式配置

`spring.datasource.hikari`：`maximum-pool-size: 20`（默认 10，压测并发下易被打满排队）、
`minimum-idle: 5`、`connection-timeout: 3000` 等。
**验证**：`/actuator/metrics/hikaricp.connections.max` = **20**。

> Redis 侧未启用 Lettuce 连接池：Lettuce 默认在单条连接上多路复用、已足够，且连接池需额外引入
> `commons-pool2`，主要收益在阻塞命令场景，本项目用不上，故不动。

---

## P3-1 消除 N+1 Redis 往返（2026-10-07，先实测确认再修）

### 问题（由对照压测实测发现，不是纸面分析）
读接口每篇笔记要做 5 次 Redis 往返（`fillStatus` 2 次 `SISMEMBER` + `mergeCounts` 3 次 `GET`），
10 篇列表就是 **50 次往返**，而基线只需 1 条 SQL → 列表接口比基线慢 **+538%**。

### 方案
- `fillStatus`：`SISMEMBER` 改用 **pipeline**（`stringRedisTemplate.executePipelined(SessionCallback)`），
  N 篇的 2N 次判断合成 1 次往返
- `mergeCounts`：`GET` 改用 **`multiGet`（MGET）**，3N 次取数合成 1 次往返
- 结果按提交顺序回填（每篇在名单里占连续槽位），并加了越界保护

**每请求 Redis 往返：`5 × 条数` → **2 次，与条数无关**。

### 改动文件
- `service/NoteService.java`：`fillStatus` / `mergeCounts` 改写为批量；补充 `RedisOperations` / `SessionCallback` / `SetOperations` / `ArrayList` 导入

### 踩坑点
- **`executePipelined` 有固定开销**：单篇（`detail`）场景下，批量化的收益被回调开销抵消，实测**没有改善**；
  条数越多收益越明显（10 篇时 -75%）。所以**批量只对列表类接口有意义**。
- **pipeline 结果按提交顺序返回**，必须用「每篇占连续槽位」的方式回填，否则会把 A 的点赞状态安到 B 上。
  本次用接口层抽查核对过（note 30 的 `liked`/`likeCount` 与 Redis 直查完全一致）。
- 批量读**没有**动写入侧（Lua 仍是单篇原子操作）——批量化只适用于读。

### 验证（已实测）
同一套环境、同一份 JMeter 计划复测（1000 请求/场景，零错误）：

| 场景 | 修复前 | 修复后 | 变化 |
|---|---|---|---|
| 列表（10 篇） | 43.4 ms | **11.0 ms** | **-75%** |
| 热榜（10 篇） | 44.7 ms | **12.7 ms** | **-72%** |
| 搜索（20 条） | 134.1 ms | **29.0 ms** | **-78%** |
| 详情（1 篇） | 9.9 ms | 10.2 ms | 无变化（见踩坑点） |

回归测试 `./ci.sh` → 9/9 通过。完整对照见 [docs/PERF.md](docs/PERF.md)。

---

## N-1 ~ N-3 面试叙事材料（2026-10-07）

新增三份文档（非代码）：
- [README.md](README.md)【新增】：技术栈、架构图、技术演进线、
  关键设计决策（可直接当讲稿）、快速开始、已知限制
- [docs/PERF.md](docs/PERF.md)【新增】：压测数据记录
- [docs/INTERVIEW-QA.md](docs/INTERVIEW-QA.md)【新增】：14 条高频追问与答案，
  全部对应当前实现、可指到具体文件

### 追加：优化前后对照压测（实做，非纸面）

用 `git worktree` 取基线 `60bb248`（早期：全部直查 MySQL）与 HEAD，
两边都指向独立压测库 `xhs_bench`（3000 笔记 / 15 万点赞），
用**同一个** JMeter 计划 `docs/bench/bench.jmx` 各压 1000 请求（50 线程 × 20 循环，零错误）。

| 场景 | 基线 | 优化后 | 变化 |
|---|---|---|---|
| 详情（1 篇） | 6.5 ms | 9.9 ms | +52% |
| 推荐页（10 篇） | 6.8 ms | 43.4 ms | **+538%** |
| 热榜（10 篇） | 9.5 ms | 44.7 ms | +370% |
| 搜索 | 6.9 ms | 134.1 ms | **+1843%** |
| 点赞 | 3.6 ms | 5.3 ms | +47% |

**结论（诚实）**：优化后的**读接口更慢**。根因是**读路径 N+1 Redis 往返**——
`fillStatus` 每篇 2 次 `SISMEMBER`、`mergeCounts` 每篇 3 次 `GET`，10 篇列表就是 50 次往返，
而基线只需 1 条 SQL。数据自证：`detail`（1 篇）只慢 3.4 ms，`list`（10 篇）慢 36.6 ms，
差值 ≈3.7 ms/篇，恰与「每篇 5 次往返」吻合。

**这直接实测确认了 P3-1（消除 N+1）是当前第一优先级的性能项**，而不是纸面猜测。
同时印证一个常被误解的点：**缓存不等于更快**——回源本来只有几毫秒时，加网络往返就是负优化。

**纪律**：简历/面试只写真实测过的数字。当前实测是"优化后更慢"，
所以**不要写"QPS 提升 N 倍"**；可以讲的是"我做了对照压测、定位并量化了一个性能回归"。
修完 N+1 复测后，才有资格谈提升。

---

## P2-6 CI 校验脚本（ci.sh）（2026-10-07）

### 决策
仓库 remote 是 **Gitee**（`gitee.com/uncleliang/xhs-project-batch34`），GitHub Actions 不会自动触发；Gitee Go 的流水线格式冷门且本机无法实测。故**不接具体 CI 平台**，改为提供一个可被任意 CI 平台（或本地）直接复用的 `ci.sh`。

### 方案
`ci.sh`：
- 默认 `mvn -B clean verify`（编译 + 打包 + Testcontainers 集成测试）
- 先探测 `docker info`，Docker 不可用则给出明确提示（因为集成测试要起临时容器）
- `./ci.sh --skip-tests` → 只编译打包，不需要 Docker

接入任何 CI 时，只需要让流水线执行这一个脚本即可（GitHub Actions / Gitee Go / Jenkins 都一样）。

### 验证（已实测）
`./ci.sh` → `Tests run: 9, Failures: 0, Errors: 0`，`BUILD SUCCESS`，输出「校验通过」。

---

## P2-8 DB 迁移工具（Flyway/Liquibase）— 暂缓（2026-10-07）

### 决策
**暂缓，保留 `init.sql` 作为唯一 schema 真源。**

### 理由
P0-1 的红线是「`init.sql` 是库表结构的唯一真源」。引入 Flyway 会产生**两份 schema 定义**（`init.sql` 给 Docker 首次建库 + `db/migration/V*.sql`），除非把 `init.sql` 整个交给 Flyway 管（Docker 只建空库、由应用启动时跑迁移）——那是一次改变建库流程的较大重构。

而当前项目 schema 变更频率低，P0-1 已把 `init.sql` 与线上库对齐、并修好了 compose 的挂载缺失，**没有实际痛点**。此时引入迁移工具属于"为了有而有"，反而增加漂移面。

**什么时候该回头做**：schema 开始频繁演进、或多环境（dev/staging/prod）需要各自演进时，再按"Flyway 单一真源"方案一次性迁过来。

---

## P2-3 API 文档（springdoc / Swagger UI）（2026-10-07）

### 问题
没有接口文档，前后端联调只能靠读代码。

### 方案
- `springdoc-openapi-ui` **1.7.0**（**1.x 对应 Spring Boot 2.x，2.x 才对应 Boot 3**）
- `OpenApiConfig`：填文档信息 + 声明 `bearerAuth` 安全方案，使 Swagger UI 上可直接 Authorize 后调受保护接口
- 生产关闭：`application-prod.yml` 里 `springdoc.api-docs.enabled=false` + `swagger-ui.enabled=false`

### 改动文件
- `pom.xml`：springdoc 依赖（版本属性 `springdoc.version`）
- `config/OpenApiConfig.java`【新增】
- `src/main/resources/application-prod.yml`：关闭文档

### 踩坑点
- **版本要对齐大版本**：Spring Boot 2.x 必须用 springdoc **1.x**；用 2.x 会因 Spring Framework 版本不兼容而启动失败。
- **要把 JWT 方案写进 OpenAPI**：否则 Swagger UI 里调受保护接口只能手动加 header；声明 `SecurityScheme(HTTP/bearer)` 后右上角会出现 Authorize 按钮。
- **生产必须关掉文档**：接口清单也是攻击面（暴露内部接口、参数）。本项目 actuator 也是同样思路（只暴露三个端点）。
- 文档路径 `/swagger-ui/**`、`/v3/api-docs/**` 不在 `/api/**` 下，因此**不受认证与限流拦截器影响**。

### 验证（已实测）
- dev：`GET /v3/api-docs` → OpenAPI 3.0.1、标题 `xhs-backend API`、安全方案 `[bearerAuth]`、**16 个接口**；`/swagger-ui/index.html` → 200
- prod：`/v3/api-docs` 与 `/swagger-ui/index.html` 均 **404**（已关闭）

---

## P2-5 后端 Dockerfile + compose 修复（2026-10-07）

### 问题
1. 后端跑不进容器（compose 里只有中间件）
2. **`docker-compose.yml` 注释声称"首次启动自动执行建库建表"，但根本没挂载 `init.sql`** → 全新环境 `compose up` 起来是个**空库**（这正是 P0-1 结构漂移的根源）
3. ES 的 IK 插件是 `docker exec` 装进运行中容器的，容器一重建就丢

### 方案
- `Dockerfile`：多阶段（maven:3.9-eclipse-temurin-8 构建 → eclipse-temurin:8-jre 运行）；pom 单独一层缓存依赖
- `.dockerignore`：排除 target/.git/.idea
- `es/Dockerfile`：`ES 8.8.2` + `analysis-ik` 插件固化进镜像
- `docker-compose.yml`：
  - mysql 挂载 `../sql/init.sql` → `/docker-entrypoint-initdb.d/01-init.sql`
  - elasticsearch 由 `image:` 改为 `build: ./es`
  - 新增 `backend` 服务，放进 **`app` profile**：`docker compose up -d` 只起中间件（开发时后端仍在 IDE 跑），`docker compose --profile app up -d` 才连后端一起起
  - 删掉过时的 `version:` 字段

### 踩坑点
- **pom 引用了 `../sql/init.sql`**（P2-2 的测试资源拷贝），但 Docker 构建上下文是 `xhs-backend/`，`../sql` 不存在 → 在 Dockerfile 里先 `mkdir -p /sql` 建个空目录兜底，否则 `process-test-resources` 阶段会报错。**pom 依赖构建上下文之外的路径，是跨构建方式的隐性耦合。**
- **挂载 init.sql 对已有数据卷不生效**：MySQL 只在数据目录为空时执行 `docker-entrypoint-initdb.d`。所以本次重建不会重跑脚本（数据安全），要真正验证"全新环境能自动建库"必须清空数据卷——这正是该挂载存在的意义。
- **业务错误与认证错误的 HTTP 语义不一致（既有设计，非本次引入）**：无 token 访问受保护接口 → HTTP 200 + body `code=401`；无效 token → 真 HTTP 401。前端两种都能处理（axios 拦 HTTP 401 清登录态，业务 code 走 toast）。若想统一需另议。
- 构建耗时主要在 `dependency:go-offline`（约 9 分钟，首次下全部依赖）；之后改源码重建只需十几秒（依赖层命中缓存）。

### 验证（已实测）
- `docker compose --profile app build` → `xhs-backend-backend`、`xhs-backend-elasticsearch` 两个镜像构建成功
- `docker compose --profile app up -d` → 5 容器运行；mysql/es 因配置变更被重建，**数据卷保留**（笔记数仍 20）
- 新 ES 容器 `bin/elasticsearch-plugin list` → `analysis-ik`（插件已固化，重建不再丢）
- 后端容器 8080：登录 200（JWT+BCrypt）、搜「三亚」经容器内 ES+IK 返回 3 条带游标、`/actuator/health` overall UP

---

## P2-4 多环境配置（2026-10-07）

### 问题
地址、账号密码全写死在 `application.yml`，没有 profile，改环境只能改代码。

### 方案
- `application.yml`：**公共配置**；环境相关项一律 `${ENV:默认值}` 占位（默认值 = 本地开发值，保证开箱能跑）
  DB_URL / DB_USERNAME / DB_PASSWORD / REDIS_HOST / REDIS_PORT / RABBITMQ_* / JWT_SECRET / ES_BASE_URL / SERVER_PORT
- `application-dev.yml`：打印 SQL、`com.xhs: debug`、health `show-details: always`
- `application-prod.yml`：关闭 SQL 打印、`com.xhs: info`、health `show-details: never`
- `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:dev}`（默认 dev）

### 改动文件
- `src/main/resources/application.yml`（改为占位符 + 公共项）
- `src/main/resources/application-dev.yml`【新增】
- `src/main/resources/application-prod.yml`【新增】

### 踩坑点
- **占位符必须带默认值**：只写 `${DB_PASSWORD}` 而没默认值，本地直接启动会因缺变量失败（`${DB_PASSWORD:123456}` 才是"可覆盖但不强制"）。
- **哪些配置属于哪个 profile 要想清楚**：日志级别、SQL 打印、health 细节这类"环境差异"放 profile；端口、连接串、密钥这类"环境相关"用占位符放公共文件。二者是两回事。
- 测试不受影响：`@SpringBootTest` 里用 `properties=` 覆盖的值优先级高于 profile 文件。

### 验证（已实测）
```
SPRING_PROFILES_ACTIVE=prod SERVER_PORT=8082 mvn spring-boot:run
```
- 日志 `The following 1 profile is active: "prod"`、端口被环境变量改到 8082
- 触发查询后 `==> Preparing:` 计数为 **0**（prod 不打印 SQL）
- `/actuator/health` 只返回 `{"status":"UP"}`（无组件细节）
- `mvn test` 仍 9/9 全绿（dev 默认 profile 下 SQL 打印照常）

---

## P2-2 核心集成测试（Testcontainers）（2026-10-07）

### 问题
`src/test` 不存在，零测试。项目最核心的逻辑（Lua 原子幂等、缓存重建/防穿透、对账）都依赖真实 Redis/MySQL，纯 mock 测不到。

### 方案
Testcontainers singleton 容器 + `@SpringBootTest`：
- 临时容器：MySQL 8.0（用 `init.sql` 建库灌种子）、Redis 7、RabbitMQ 3.12
- **ES 不启容器**：把 `xhs.es.base-url` 指向不可达端口 `http://localhost:1`，既完全隔离，又顺带覆盖「ES 不可用 → 回退 MySQL」降级路径
- 定时任务通过新增的可配置延迟调到 1 天后执行，避免测试期间并发改动 Redis/热榜

测试覆盖（9 个用例，全绿）：
| 测试类 | 覆盖 |
|---|---|
| `InteractServiceTest` | Lua 幂等：重复点赞只 +1、取关后可再赞、**100 并发点赞只产生 1 条关系**、并发取关不变负 |
| `NoteCacheTest` | 缓存三兄弟：未命中回源 + 正常 TTL、**空对象缓存防穿透**（含 60s 短 TTL 断言）、命中缓存 |
| `ReconcileTaskTest` | 对账：Redis 清空后按 MySQL 回填、重复执行幂等 |

### 改动文件
- `pom.xml`：testcontainers `junit-jupiter/mysql/rabbitmq`（**需显式版本**）+ `maven-resources-plugin` 把 `../sql/init.sql` 拷进 test classpath
- `src/test/java/com/xhs/AbstractIntegrationTest.java`【新增】：容器与动态属性
- `src/test/java/com/xhs/service/{InteractServiceTest,NoteCacheTest,ReconcileTaskTest}.java`【新增】
- `ReconcileTask` / `HotRankTask`：`@Scheduled` 延迟改为可配（`initialDelayString`）
- `EsService`：ES 地址改为可配 `xhs.es.base-url`（默认还是 `http://localhost:9200`）

### 踩坑点
1. **Spring Boot 2.7 的 BOM 不含 testcontainers**：不加 `<version>` 直接 `'dependencies.dependency.version' ... is missing` 构建失败。需自己定版本（用 1.19.8，Java 8 兼容）。
2. **不要在基类上用 `@Container` 声明静态容器**：`@Testcontainers` 扩展会**每个测试类重启一次容器 → 映射端口变了**，而 Spring 上下文是跨类缓存的、`@DynamicPropertySource` 只在首次建上下文时求值 → 第二个测试类连的是**已失效的旧端口**，报 `Redis command timed out after 3 second(s)`。
   改用 **singleton 容器模式**（静态块里 `start()`，不标注解），容器全 JVM 存活、端口稳定。改前 5 个用例超时，改后全绿且后续类只需 0.1~0.3s。
3. **`init.sql` 不复制副本**：用 maven-resources-plugin 从 `../sql/init.sql` 拷到 test classpath，保持"脚本唯一真源"（呼应 P0-1）。
4. **定时任务会污染测试**：`ReconcileTask` 会把种子数据回填进 Redis，导致「断言 SCARD==1」之类的用例失败 → 把延迟做成可配置并在测试里调成 1 天。

### 验证（已实测）
`mvn test` → `Tests run: 9, Failures: 0, Errors: 0`，BUILD SUCCESS。

---

## P2-1 引入 Actuator（健康检查 + 指标）（2026-10-07）

### 问题
项目没有任何健康检查/指标端点，"生产化"缺少可验证的抓手。

### 方案
- `pom.xml` 加 `spring-boot-starter-actuator`
- 只暴露 `health,info,metrics`（**不暴露** `env/beans/heapdump` 等敏感端点）
- 新增 `EsHealthIndicator`：ES 用 RestTemplate 直连、Boot 无内置指标，补一个自定义 `HealthIndicator`

### 改动文件
- `pom.xml`：新增 actuator 依赖
- `application.yml`：`management.endpoints.web.exposure.include` + `show-details: always` + `info.app.*`
- `service/EsHealthIndicator.java`【新增】

### 踩坑点
- **ES 没有内置 health 指标**：MySQL(DataSource)、Redis、RabbitMQ 都由 Boot 自动装指标，ES 因为走 RestTemplate 直连而被漏掉 → 必须自己写 `HealthIndicator`。
- **ES DOWN 会让整体 health 变 DOWN**：本应用对 ES 是**降级可用**的（搜索回退 MySQL），所以严格说 ES 不该拖垮就绪状态。生产可用 health group 把 ES 排除在 liveness/readiness 之外。当前未做（保持简单），但要知道这个取舍。
- **不要暴露全部端点**：`exposure.include: "*"` 会把 `env`（含配置与密钥）、`heapdump` 等暴露出来；本项目又没接 Spring Security，等于公网可读。

### 验证（已实测）
```
GET /actuator/health  → {"status":"UP", components: {db,diskSpace,es{documents:20},ping,rabbit,redis} 均 UP}
GET /actuator/info    → {"app":{name,description,java,spring-boot}}
GET /actuator/env     → 404（未暴露）
GET /actuator/metrics → 70 个指标
```
故障演练：`docker stop xhs-elasticsearch` → `es` 变 `DOWN`、overall `DOWN`；`docker start` 后自动恢复 `UP`。

---

## P1-14 ES 接入 IK 分词（2026-10-07）

### 问题
索引三字段都是默认 `standard` 分词器，中文被**按单字切分**（"三亚"→"三","亚"），搜索质量差。

### 方案
- 容器装 `analysis-ik 8.8.2`（`docker exec xhs-elasticsearch bin/elasticsearch-plugin install -b https://get.infini.cloud/elasticsearch/analysis-ik/8.8.2`）并重启
- mapping 三字段改为 `analyzer: ik_max_word`（建索引）+ `search_analyzer: ik_smart`（搜索）
- **附带修正**：`multi_match` 加 `"operator": "and"`

### 为什么必须加 operator=and
IK 词典未必收录业务词（如「清补凉」），此时 `ik_smart` 会退化成单字 `["清","补","凉"]`；
而 `multi_match` 默认 `operator=or` → **只要命中其中一个字就算匹配**。
实测：搜「清补凉」ES 返回 3 条，而 MySQL 精确子串只有 2 条——多出的那条只含「凉」。
加 `and` 后要求全部分词命中，结果与精确匹配一致。

### 踩坑点
- **插件装在容器里、不在镜像里**：`docker compose down/up` 重建容器会丢失，需重装（建议写进 Dockerfile）。
- **换分词器必须重建索引**：`analyzer` 是**建索引时**生效的，改 mapping 对已有索引无效，必须删除索引重建 + 回填（正好由 P1-15 承接）。
- **别用中文做 shell 传参**：`curl -d '{...中文...}'` 在中文 Windows 上会按 GBK 发出，ES 报 `Invalid UTF-8`。验证脚本改用 Python 发送。

### 验证（已实测）
```
standard   : 三亚三天两夜超全攻略 → 10 个单字
ik_smart   : 三亚三天两夜超全攻略 → 5 个词
ik_max_word: 三亚三天两夜超全攻略 → 12 个词
```
搜「三亚」6 条、与 MySQL 精确匹配数一致；搜「清补凉」由 3 条（误召回）修正为 2 条。

---

## P1-15 ES 存量数据回填（2026-10-07）

### 问题
索引重建（如换分词器）后没有程序化的数据恢复手段，只有手工 `benchmarks/notes-backfill.ndjson`。

### 方案
`EsBackfillRunner`（`@Order(3)`）：启动时 `ensureIndex()` → `count()`，为 0 则从 MySQL 全量 `_bulk` 回填。
- 自带 `ensureIndex()`：本 Runner 的 order 早于无 `@Order` 的 `EsInitRunner`，必须自己保证索引存在
- `EsService` 新增 `count()` 与 `bulkIndex(List<Note>)`（用 `ObjectMapper` 生成 NDJSON）

### 验证（已实测）
删除索引后重启：日志 `ES 索引 xhs_notes 创建成功（IK 分词）` + `ES 批量回填 20 篇笔记`；ES 文档数 == MySQL（20）。

---

## P1-16 ES 深分页 from/size → search_after（2026-10-07）

### 问题
搜索用 `from/size`，深分页时 ES 需在每个分片上取 `from+size` 条再归并，页越深越贵。

### 方案
游标分页（`search_after`）：
- 排序 `[{_score: desc}, {note_id: asc}]`（相关度优先 + 唯一 tiebreaker 保证翻页不重不漏）
- 响应返回 `nextCursor`（本页最后一条的排序值 `score:noteId`），客户端下次原样传回 `cursor`
- API 由 `Result<List<NoteVO>>` 改为 `Result<SearchPageVO{list, nextCursor}>`；前端搜索页加「加载更多」
- ES 降级到 MySQL 时无游标语义 → 只返回第一页（`nextCursor=null`）

### 踩坑点（本次卡住最久的一个）
- **ES 8 默认禁止对 `_id` 排序**：`{"_id":"asc"}` 会报
  `Fielddata access on the _id field is disallowed`，整个 `_search` 400。
  **而这个 400 被 `NoteService` 的 try/catch 吞掉、静默降级到 MySQL**——表面上"有结果返回"，实际根本没走 ES。
  解法：索引里加一个 `note_id`（long）字段专门做排序 tiebreaker。
  **教训**：降级兜底会让上游故障"看起来很健康"，验证时必须确认走的是主路径（本次靠"响应里有没有游标"区分出来）。
- `search_after` 的游标值**类型必须与 sort 字段一致**（score 是 double、note_id 是 long），所以游标解析时分别用 `Double.parseDouble` / `Long.parseLong`。

### 验证（已实测）
搜「三亚」`size=2` 逐页：`[16,3]` → `[1,6]` → `[17,8]` → `[]`，共 6 条、去重后仍是 6、**无重复无遗漏**；`nextCursor` 由 ES 返回（证明走的是 ES 主路径而非降级）。

---

## P1-13 限流改为按用户维度（2026-10-07）

### 问题
`RateLimitInterceptor` 原是**全局窗口**（`rate:limit:like:{秒}`），所有用户共用一个桶；且它注册在 `AuthInterceptor` **之前**，拿不到 `userId`。

### 方案
- **调整拦截器顺序**：auth 在前（写入 `UserContext`），限流在后 —— 这样限流才能按用户
- **Key 加主体维度**：`rate:limit:{u:userId | ip:IP}:{分类}:{秒}`；登录用户按 userId，匿名按来源 IP（否则匿名请求全挤一个桶）
- **阈值改单用户量级且 yml 可配**：`xhs.rate-limit.{like:20, comment:5, search:10, default:50}`（评论 5/秒是选做挑战的指定值）

### 改动文件
- `application.yml`：新增 `xhs.rate-limit.*`
- `config/WebConfig.java`：auth 拦截器提到限流之前
- `ratelimit/RateLimitInterceptor.java`：阈值改为 `@Value` 注入；Key 拼 principal；新增 `clientIp()`（优先 `X-Forwarded-For` 第一段）

### 踩坑点
- **拦截器顺序即依赖顺序**：限流要用身份，就必须排在认证之后。反过来（限流在前）也能跑，但拿不到 userId，只能退化成全局/IP 维度。
- 代价：认证前不再限流。这里可接受（JWT 验签很轻），生产中若担心可再加一层纯 IP 的前置限流。
- **调大阈值 ≠ 按用户维度**：原全局阈值（点赞 1000/秒）换成单用户后若沿用，等于放宽了两个数量级、基本限不住。必须换成单用户量级。

### 验证（已实测）
> `mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dxhs.rate-limit.search=3" "-Dspring-boot.run.arguments=--server.port=8081"`

- user1 并发 10 次搜索 → **3× 200 + 7× 429**
- user2 并发 3 次 → **3× 200**（各用户独立计桶）
- Redis key：`rate:limit:u1:search:<秒>`、`rate:limit:u2:search:<秒>`

### 附带修复（本次暴露的两个真问题）
1. **Maven 源码编码未声明** → 中文 Windows 下 `javac` 按 GBK 读 UTF-8 源码，报「非法字符」；之前一直没暴露是因为 `target/classes` 里的 class 是 IDE 编的、Maven 增量判定跳过编译。已在 `pom.xml` 显式声明 `<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>`（及 reporting 编码），并首次跑通 `mvn clean compile`。
2. **javadoc 里写了 `*/`**：注释中的路径 `/api/notes/**/like` 含 `*/`，会**提前终止块注释**，后续中文被当作代码 → 「非法字符」。已把路径改为 `/api/notes/{id}/like`。**不要在注释里写含 `*/` 的路径**。

---

## P1-11 + P1-12 热度时间衰减 & 热榜定时重算（2026-10-07）

### 问题
`HotService.addHeat` 是纯 `ZINCRBY` 累加，**只增不减** → 早期爆款永久霸榜；且实时累加的误差无处修正。

### 方案
两项本质是同一个定时任务，合并实现——定时按「基础分 × 时间衰减」整体重写 `hot:notes`：

```
base  = like×1 + comment×5 + favorite×2          （基础分，直接取 DB 真实计数）
score = base × 0.5 ^ (龄期小时 / 半衰期小时)      （半衰期指数衰减，默认 12h）
```

- `@Scheduled(initialDelay=30s, fixedDelay=10min)`
- 与实时 `addHeat` **并存**：重算负责「纠偏 + 衰减」，`addHeat` 负责「立刻可见」；重算覆盖写时会把期间的实时增量按 DB 真实计数一并纳入

### 改动文件
- `application.yml`：新增 `xhs.hot.half-life-hours: 12`
- `service/HotService.java`：新增 `setScore(noteId, score)`（覆盖写）
- `service/HotRankTask.java`【新增】：定时重算 + 衰减

### 踩坑点 / 已知局限
- **衰减到极小值后，实时 `addHeat` 的"跳变"会被放大**：老笔记的基础分衰减到 ~1e-27，此时一次点赞的 `ZINCRBY +1` 会让它瞬间冲到榜首，直到下一次重算（≤10 分钟）拉回。彻底解决要么让 `addHeat` 按龄期加权、要么缩短重算周期；当前按「生产常见做法：定期重算 + 接受区间内漂移」处理。
- **本项目内容都太老**（种子笔记约 45 天龄），衰减后半衰期 12h 下几乎所有分数都趋近 0，榜单排序区分度低——调大 `half-life-hours` 可缓解。
- 重算与 `InitHotRunner` 有重叠：`InitHotRunner` 只在榜单为空时按未衰减的基础分冷启动，30 秒后就会被本任务覆盖。保留它是为了对齐手册要求，二者可择一。

### 验证（已实测）
- 日志：`【热榜重算】20 篇笔记已按半衰期 12.0 小时衰减重算`
- 逐条比对 `ZSCORE hot:notes` 与按公式算出的期望值：**8/8 完全吻合**（误差在第 6 位有效数字内）
  `note 1: base=40 age=1144.8h expected=7.6481e-28 actual=7.6499e-28 OK`

---

## P1-10 Feed 收件箱裁剪（2026-10-07）

### 问题
`feed:{userId}` / `feed:outbox:{authorId}` 只增不减，长期无限膨胀（Redis 内存）。

### 方案
每次写入后按排名裁剪，只保留最新 `xhs.feed.keep` 条（默认 100，yml 可配）：
`ZREMRANGEBYRANK key 0 -(keep+1)` —— 保留 score 最大的 keep 条，删掉更旧的。

### 改动文件
- `application.yml`：新增 `xhs.feed.keep: 100`
- `service/FeedService.java`：新增 `trim(key)`；`pushNote` 写发件箱后裁一次、写每个粉丝收件箱后各裁一次

### 踩坑点
- **裁剪是"每次写都做"**：普通作者发布时，每个粉丝的收件箱都会多一次 `ZREMRANGEBYRANK`，写放大从「1 次 ZADD」变「1 次 ZADD + 1 次裁剪」。条数很少时开销可忽略，粉丝量极大时可考虑改为定时批量裁剪。
- **裁剪会丢历史**：超过 keep 条的旧笔记不再出现在关注页——这是有意的（收件箱本来就是"最近动态"），但要想清楚 keep 的取值。
- **裁剪不影响兜底**：收件箱被裁空后，`followFeed` 会自动回落到 MySQL 联表查询，不会白屏。

### 验证（已实测）
> 运行：`mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dxhs.feed.keep=2" "-Dspring-boot.run.arguments=--server.port=8081"`

- user 7 连发 4 篇后：`ZCARD feed:2` = 2（原本 7，被裁到 2）、`ZCARD feed:outbox:7` = 2
- `ZRANGE feed:2 0 -1` 只留最新的 `28 29`，最早的两条被裁掉
- 测试笔记与 ZSet/ES 数据已清理

---

## P1-9 大V推拉结合（2026-10-07）

### 问题
`FeedService.pushNote` 无条件把新笔记写进**每个粉丝**的收件箱（写扩散）。粉丝越多写越贵，千万粉大V发布一次就是千万次写。

### 方案
**作者发件箱 + 大V只写自己、粉丝读时来拉（推拉结合）**

```
发布:  写 feed:outbox:{authorId}  (1 次)
       ├─ 粉丝数 <= 阈值: 再推给每个粉丝 feed:{fanId}   (推模式)
       └─ 粉丝数 >  阈值: 不再推（拉模式）

读关注页: 合并「feed:{me}」 + 所关注大V的「feed:outbox:{bigV}」→ 按 score 倒序分页
```

- 阈值 yml 可配：`xhs.feed.big-v-fans-threshold`（默认 1000）
- 大V判定：`SELECT COUNT(*) FROM t_follow WHERE follow_user_id=?` 与阈值比较

### 改动文件
- `common/RedisKeys.java`：新增 `feedOutbox(authorId)` → `feed:outbox:{id}`
- `application.yml`：新增 `xhs.feed.big-v-fans-threshold`
- `mapper/FollowMapper.java`：新增 `selectBigVFolloweeIds(userId, threshold)`（我关注的、且粉丝数超阈值的用户）
- `service/FeedService.java`：`pushNote` 写发件箱 + 按阈值决定是否推；`feedIds` 改为「收件箱 + 大V发件箱」归并排序分页

### 设计说明（为什么用 outbox ZSet 而不是读时查库）
拉模式常见的实现是「读时查作者最新笔记」。这里改用**每个作者一个 outbox ZSet**（发布时只写 1 条）：
- 归并排序时两个来源的 score 都是毫秒时间戳，**避免了 DB `create_time`（秒精度、需处理时区）与 Redis score 混排的麻烦**
- 大V 的写入成本从「粉丝数」降到「1」

### 踩坑点
- **推拉归并的分页是"快照式"的**：先取两个来源各 `page*size` 条再归并切片，数据在翻页过程中变动时可能出现重复/遗漏。这是所有 Feed 分页的通病（时间线不断有新数据），本项目可接受。
- **阈值必须可配**：本项目最大粉丝数才 6，写死 1000 的话大V分支永远进不去、没法验证也不能演示。

### 验证（已实测）
> 运行：`mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dxhs.feed.big-v-fans-threshold=3" "-Dspring-boot.run.arguments=--server.port=8081"`
> （注意：`spring-boot.run.arguments` 的逗号分隔在 CLI 上不生效，多个覆盖项要用 `jvmArguments` 传系统属性）

阈值=3 时 user 2（6 粉）为大V、user 7（2 粉）为普通：
- 大V发布 → `feed:1` 无该笔记（未推）、`feed:outbox:2` 有；user1 的关注页**首条即该笔记**（拉到了）；日志 `作者 2 粉丝数 6 超过阈值 3，走拉模式`
- 普通发布 → `feed:2`、`feed:5` 都有（已推）
- 测试笔记与 ZSet/ES 数据已清理

---

## P1-3 关注/取关后失效 user:{id} 缓存（2026-10-07）

### 问题
`user:{id}` 缓存里含 `followCount` / `fansCount`，但 `InteractService.follow` / `unfollow` 只写 MySQL，从不碰缓存 → 关注后双方主页数字最长陈旧 30 分钟。

### 方案
关注是**双向影响**：我 `followCount`+1、对方 `fansCount`+1，所以**两个 key 都要删**。用标准 Cache-Aside 的写失效：
- `follow` 插入成功后 → 删 `user:{userId}` + `user:{targetUserId}`
- `unfollow` 删除行数 > 0 时才删（无变化不必惊动缓存）
- 关注本身是 MySQL 同步写，缓存下次读取时按 DB 重建，天然新鲜

### 改动文件
- `service/InteractService.java`：`follow`/`unfollow` 成功后调用新增私有方法 `evictUserCache(...)`；复用已有的 `stringRedisTemplate`（key 由 `StringRedisSerializer` 编码，两个 template 的 DEL 等价，无需再注入一个）

### 踩坑点
- **双向关系容易漏删一半**：只删自己的 `user:{me}` 会漏掉对方的 `user:{target}`，对方的 `fansCount` 依旧陈旧。凡是"关系型"写操作，都要把受影响的两个主体都失效。
- 删除 key 用哪个 RedisTemplate 都行（key 序列化器相同），不必为了删缓存再注入一个 JSON 的 `RedisTemplate`。

### 验证（已实测）
- 预热 `user:6` / `user:4` 缓存（`EXISTS=1`）
- user 6 关注 user 4 → 两个 key 都变 `EXISTS=0`
- `GET /api/users/4` → `fansCount` 0 → 1；`GET /api/users/6` → `followCount` 1 → 2
- 取关后 `fansCount` 还原为 0（测试关系已回滚）

---

## P1-7 对账定时任务（+ 顺带修复「已点赞」状态 bug）（2026-10-07）

### 问题
1. 手册要求：`SCARD like:{id}` 与 `COUNT(t_note_like)` 对账，是"最终一致"故事的闭环，原本没有。
2. 实测发现 Redis 里互动数据几乎全空（`SCARD=0`、`count=nil`），而 MySQL 有种子数据 → 冷启动/种子未同步。
3. **顺带暴露一个真 bug**：`NoteService.fillStatus` 用 `redisTemplate`（JSON 序列化）做 `isMember`，会把成员 `"3"` 序列化成带引号的 `"3"`，与写侧（Lua / `StringRedisTemplate` 写入的裸 `3`）对不上 → **所有"已点赞/已收藏"状态恒为 false**。之前 Redis 为空所以没暴露。

### 方案
**以 MySQL 为准，对 Redis 做「只增不删」的增量回填**
- 对每类互动（like / favorite / share）：读全表 → 按 noteId 分组 → `SADD` MySQL 里的成员 → 计数对齐为 Set 实际基数
- **不整体重建（不 DEL）**：本项目是「Redis 先写 → MQ → MySQL」，对账那一刻可能有"已写 Redis、消息还在队列里"的关系，整体重建会误删在途数据
- 用 `StringRedisTemplate`（与写侧同序列化）
- `@Scheduled(initialDelay=10s, fixedDelay=10min)`

**顺带修复**：`fillStatus` / `mergeCounts` 改用 `StringRedisTemplate`，读写序列化成对。

### 改动文件
- `XhsApplication.java`：加 `@EnableScheduling`
- `service/ReconcileTask.java`【新增】：对账任务
- `service/NoteService.java`：`fillStatus` / `mergeCounts` 改用 `StringRedisTemplate`（**bug 修复**）

### 踩坑点
- **同一批 key 读写必须用同一种序列化器**：写侧从 `RedisTemplate`(JSON) 换成 `StringRedisTemplate` 后，读侧 `isMember` 忘了换，成员变成 `"3"` vs `3`，静默失配——不报错、只是恒 false，极难发现。（呼应既有经验：互动类 Set 必须整体用 StringRedisTemplate）
- **计数读取"看起来正常"具有欺骗性**：`mergeCounts` 用 JSON 读裸数字 `7`，Jackson 恰好能解析成 Integer，所以计数一直是对的；只有 Set 成员比对因为按字节/字符串比较才暴露。**不能因为一个读路径正常就认为另一个也正常**。
- **只增不删的取舍**：能安全修复"MySQL 有、Redis 无"，但修不了"Redis 有、MySQL 无"（消息丢失/进 DLQ）——后者需人工看 DLQ 处理。

### 验证（已实测）
- 对账首轮：`【对账】点赞 noteId=1 Redis 集合 0 → 7` 等，Redis 被按 MySQL 回填；`SCARD like:1=7`、`like:count:1=7`
- 对账第二轮：`回填 0 个不一致集合`（幂等）
- 修复后接口：用户 3（种子里赞过 note 1）`GET /api/notes/1` → `liked=true`；用户 2（未赞）→ `false`

---

## P1-6 配置死信队列 DLX（2026-10-07）

### 问题
5 个队列都没有 DLX，消费者抛异常后 `default-requeue-rejected` 默认 `true` → **失败消息无限重投**，一条坏消息能永久堵住队列（`EsConsumer` 的 `throw e` 注释里已自认）。

### 方案
**每条业务队列一个 DLQ + 本地重试耗尽后进死信**

```
xhs.exchange (topic)                         xhs.dlx (direct)
  like.db.#        → like.db.queue     ─┐
  favorite.db.#    → favorite.db.queue  ├─ x-dead-letter-routing-key = 队列名
  share.db.#       → share.db.queue     │
  comment.notify.# → comment.notify.queue
  note.es.#        → note.es.queue     ─┘
                                          → like.db.dlq / favorite.db.dlq /
                                            share.db.dlq / comment.notify.dlq / note.es.dlq
```

- 队列参数：`x-dead-letter-exchange=xhs.dlx` + `x-dead-letter-routing-key=<队列名>`
- 重试：`listener.simple.retry` 3 次（1s/2s 退避）；耗尽后 `MessageRecoverer` 记录日志并抛 `AmqpRejectAndDontRequeueException` → 拒绝且不重回 → 进 DLQ
- `default-requeue-rejected: false`：非重试路径的异常也拒绝而非无限重投

### 改动文件
- `config/RabbitConfig.java`：新增 `xhs.dlx` + 5 个 DLQ + 5 条绑定；5 个业务队列加 DLX 参数；新增 `MessageRecoverer` Bean（记日志 + 拒绝）
- `application.yml`：新增 `listener.simple.retry` 与 `default-requeue-rejected: false`

### 踩坑点（重要，运维必读）
- **RabbitMQ 队列参数不可变**：给已存在的队列换参数（如加 `x-dead-letter-exchange`）会报
  `PRECONDITION_FAILED - inequivalent arg ... received 'xhs.dlx' but current is none`，应用起不来。必须**先删除旧队列**（无积压时无损失）再让应用重建。
- **同一套 broker 上跑着旧版本实例会"复活"旧拓扑**：删掉队列后，旧实例（8080）因 Spring AMQP 连接恢复会自动按旧参数重新声明队列，把新拓扑顶掉。**改队列参数时必须确保没有旧实例在跑**。
- 死信消息**不会被自动消费**，需人工/运维查看 `*.dlq`；生产应配告警（当前仅落在队列里）。
- 本地重试是**在应用内存中**做的（`stateless`），应用重启会丢失重试进度（消息仍在原队列，会被重新投递）。

### 验证（已实测）
投递一条必然失败的消息 `{"noteId":1,"userId":null,"liked":true}` 到 `like.db.save`：
- 日志：`【MQ 消费失败→死信】body={...}, cause=...threw exception`
- 结果：`like.db.queue=0`、`like.db.dlq=1`
- 回归：投递正常消息（`like.db.cancel` 不存在的关系）能被正常消费，`like.db.queue` 回到 0、DLQ 不增长
- 测试死信已 purge

---

## P1-5 开启 publisher confirm + returns（2026-10-07）

### 问题
手册明说发布端确认「未开启」；消息发出去后是否到达交换机 / 能否入队，应用层完全无感知。

### 方案
开启 Spring AMQP 的发布端可靠性三件套，并注册回调把失败"变得可见"：

| 配置 | 作用 |
|---|---|
| `publisher-confirm-type: correlated` | 消息是否到达交换机（异步确认） |
| `publisher-returns: true` + `template.mandatory: true` | 路由不到任何队列时退回，而不是静默丢弃 |

回调语义：
- `ConfirmCallback(ack=false)` → 消息**没到交换机**（真丢了）
- `ConfirmCallback(ack=true)` → 只代表到了交换机，**不代表入队**
- `ReturnsCallback` → 到了交换机但**路由不到队列**，携带 exchange / routingKey / replyText / body

### 改动文件
- `application.yml`：新增 `publisher-confirm-type` / `publisher-returns` / `template.mandatory`
- `config/RabbitConfirmConfig.java`【新增】：构造器里给 `RabbitTemplate` 注册两个回调并打日志
- 发送侧补 `CorrelationData`（可读 id，便于定位失败消息）：
  `InteractService`（like/favorite/share）、`CommentService`（comment.notify）、`NoteService`（note.es）

### 踩坑点
- **回调不要写在 `RabbitConfig` 里注入 RabbitTemplate**：`RabbitTemplate` 是 Boot 自动装配的，用户 `@Configuration` 注入它存在 Bean 创建时序问题。改成独立的 `@Component` 用**构造器注入** `RabbitTemplate`，Spring 会保证模板先就绪。
- **`mandatory` 必须显式开**：只开 `publisher-returns` 不开 `template.mandatory`，不可路由的消息会被 broker 静默丢弃，`ReturnsCallback` 不触发。
- **`correlated` 确认类型要配 `CorrelationData`**：不传的话回调拿到的 `correlationData` 为 null，日志无法定位是哪条消息。
- 回调只负责"失败可见"，**真正的补偿是对账任务（P1-7）**，不是这里的重发。

### 验证（已实测）
真实触发不可路由路径：临时删除 `note.es.#` 绑定 → 发笔记 → 观察回调日志 → 立即恢复绑定。
```
【MQ return】消息无法路由到队列：exchange=xhs.exchange, routingKey=note.es, replyText=NO_ROUTE, body={"noteId":23}
```
同时确认 ES 未收到该文档（`total=0`），证明消息确实被退回而非入队。绑定已恢复为 `note.es.#`。

---

## P1-2 消除 note:{id} 缓存计数陈旧（2026-10-06）

### 问题
`note:{id}` 缓存里存的是「缓存时刻」的计数快照，`mergeCounts` 只覆盖了 like / favorite 两个：
- `shareCount`：Redis 有 `share:count:{id}` 计数器，但 `mergeCounts` 没读 → 详情页分享数最长陈旧 30 分钟
- `commentCount`：评论是**同步写库**的，没有 Redis 计数器，缓存里是旧值 → 详情页评论数陈旧

### 方案
| 计数 | 有 Redis 计数器？ | 处理 |
|---|---|---|
| likeCount / favoriteCount | 有 | `mergeCounts` 从 Redis 覆盖（原有） |
| shareCount | 有 | **`mergeCounts` 补读 `share:count:{id}`** |
| commentCount | 无 | **发评论后删除 `note:{id}` 缓存**（`afterCommit` 内，与评论列表缓存一起删） |

不新增 Redis 评论计数器的理由：评论本身同步写库并直接维护 `t_note.comment_count`，再引一个 Redis 计数器会多出一份需要被对账的数据；直接删缓存更简单、无额外一致性负担。

### 改动文件
- `service/NoteService.java`：`mergeCounts` 补 `shareCount`
- `service/CommentService.java`：`add` 的 `afterCommit` 中追加 `redisTemplate.delete(RedisKeys.note(noteId))`

### 踩坑点
- 三个互动计数里只有 `share` 被漏掉，属于「加功能时忘了同步读取侧」的典型漏改——新增 Redis 计数器时，**写入侧和读取侧要成对补**。
- 删 `note:{id}` 会让热点笔记的详情缓存在每来一条评论时重建一次；重建只是单条 `SELECT`，可接受。若将来评论量极大，再改成「给评论也上 Redis 计数器 + 对账」。

### 验证（已实测）
- 令 Redis `share:count:1=777`、DB `share_count=1` → `GET /api/notes/1` 返回 `shareCount=777`（走 Redis）
- 预热 `note:1`（commentCount=5）→ 发评论 → `note:1` 缓存消失（EXISTS=0）→ 再查 commentCount=6

---

## P1-1 评论列表缓存：游标分页 + 只缓存首页（2026-10-06）

### 问题
`CommentService.listByNote` 直查库，`RedisKeys.commentList` 定义了从未使用；整列表缓存在大评论量下有大 key、重建风暴、深分页等问题（选做挑战文档已分析）。

### 方案
**游标分页（新→旧）+ 只缓存第一页**
- 排序：`ORDER BY c.id DESC`（id 自增，等价时间倒序且与游标一致，排序稳定）
- 游标：`WHERE note_id=? AND c.id < lastId LIMIT size`，首页 lastId 传 null → 用 `Long.MAX_VALUE` 兜底
- 缓存：`comment:list:{noteId}:1`，TTL 5 分钟；空列表 1 分钟（防穿透）
- 失效：发评论后删首页缓存（DESC 下新评论落在首页）
- 仅「首页 + 标准页大小 20」走缓存

### 改动文件
- `common/RedisKeys.java`：`commentList(noteId)` → `commentList(noteId, page)`
- `mapper/CommentMapper.java`：`selectByNote` → `selectByNoteCursor(noteId, lastId, size)`
- `service/CommentService.java`：`listByNote` 加分页 + 首页缓存；`add` 在 `afterCommit` 删首页缓存
- `controller/CommentController.java`：新增可选参数 `lastId` / `size`
- 前端 `api/index.js`、`views/NoteDetail.vue`：`getComments(noteId, lastId)` + 「加载更多」；评论数标题改用 `note.commentCount`

### 踩坑点
- **缓存 key 不含 size 会互相污染**：若 `?size=5` 与默认 20 共用同一个 key，后写覆盖先写，默认请求会只拿到 5 条。故限定「首页 + 标准页大小」才缓存。
- **排序方向决定失效策略**：ASC 下新评论落最后一页、首页几乎不用失效；DESC 下新评论落首页、必须每次删。本项按 DESC（新→旧）实现，单次 `DEL` 开销可忽略。
- **游标用 id 而非 create_time**：id 自增单调、与 `(note_id, id)` 索引匹配；`create_time` 可能重复导致顺序不稳。

### 验证（已实测）
```bash
B=http://localhost:8080
curl -s "$B/api/notes/1/comments"                      # 首页 DESC
curl -s "$B/api/notes/1/comments?size=2"               # [14,4]
curl -s "$B/api/notes/1/comments?size=2&lastId=4"      # [3,2]
curl -s "$B/api/notes/1/comments?size=2&lastId=2"      # [1]
docker exec xhs-redis redis-cli EXISTS comment:list:1:1   # 发评论后应为 0
```
结果：首页 DESC；缓存写入 TTL≈300s；游标逐页无重叠；自定义 size 不污染默认首页缓存；发评论后缓存 key 被删且新评论置顶。

---

## P0-4 关键写链路加事务 + MQ 与事务解耦（2026-10-06）

### 问题
全项目 0 处事务；`publish` / `add` 多步写库，中途失败留脏数据；且事务内直接发 MQ 无法随回滚撤回（幽灵消息）。

### 方案选型
| 决策 | 结论 |
|---|---|
| MQ 解耦 | **`TransactionSynchronizationManager` 提交后发送**（非本地消息表 + 定时补偿） |
| 事务范围 | 业务写（`publish` / `add`）+ 消费者（Like / Favorite / Share） |

### 改动文件
- `common/TransactionHelper.java`【新增】：`afterCommit(Runnable)` 工具，无活动事务时退化为立即执行
- `service/NoteService.java`：`publish` 加 `@Transactional`；Feed 推送 + MQ 发送移入 `afterCommit`
- `service/CommentService.java`：`add` 加 `@Transactional`；通知 MQ + 热度累加移入 `afterCommit`
- `consumer/{Like,Favorite,Share}Consumer.java`：`@RabbitListener` 方法加 `@Transactional`

### 设计说明
- `afterCommit` 只在 DB 真正提交后执行副作用，回滚时不发消息 → 无幽灵消息。
- Feed 推送（Redis 写）一并移到提交后：避免笔记回滚却留下指向不存在笔记的收件箱条目。
- 消费者加事务：保证「插/删明细 + 改计数」原子。
- **局限**：进程在「提交成功」与「afterCommit 执行」之间崩溃仍会丢副作用，由 P1-7 对账任务兜底。

### 踩坑点
- `registerSynchronization` 必须在事务内调用，否则抛 `IllegalStateException`；故用 `isSynchronizationActive()` 判断，无事务时立即执行。
- MySQL 下 `DuplicateKeyException` 被 catch 不会污染事务（PostgreSQL 会 abort 整个事务），故消费者「插入冲突 → 跳过」的幂等写法与 `@Transactional` 兼容。
- `@Transactional` 必须经 Spring 代理调用才生效（Controller→Service、监听容器→listener 都满足；同类内部 `this.xxx()` 不生效）。

### 验证（已实测）
```bash
B=http://localhost:8080
T=$(curl -s -X POST $B/api/users/login -H "Content-Type: application/json" -d '{"username":"xiaohong","password":"123456"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s -X POST $B/api/notes -H "Authorization: Bearer $T" -H "Content-Type: application/json" -d '{"title":"p04","content":"c","tags":"t"}'   # → 200
curl -s -X POST $B/api/notes/22/comments -H "Authorization: Bearer $T" -H "Content-Type: application/json" -d '{"content":"hi"}'             # → 200
curl -s -X POST $B/api/notes -H "Authorization: Bearer $T" -H "Content-Type: application/json" -d '{"title":"","content":"x"}'                # → 400
```
结果：发布 200 且库中落库 + ES 收到 doc（afterCommit 的 MQ 生效）；评论后 `t_comment`=1 且 `comment_count`=1（原子）；空标题 400 且 DB 无残留行（回滚、不触发副作用）。

---

## P0-3 密码 BCrypt 加密（2026-10-06）

### 问题
`UserService.login` 明文比对；`t_user.password VARCHAR(50)` 装不下 BCrypt 的 60 字符；库与脚本里种子密码都是明文 `123456`。

### 方案选型
| 决策 | 结论 |
|---|---|
| 存量密码 | **批量迁移**为 BCrypt（保持 123456 可登录），不保留明文兼容分支 |
| BCrypt 实现 | **spring-security-crypto** 的 `BCryptPasswordEncoder`（仅引 crypto 模块，不引整个 Security Starter） |

### 改动文件
- `pom.xml`：新增 `org.springframework.security:spring-security-crypto`（版本由 Spring Boot BOM 管理）
- `config/PasswordConfig.java`【新增】：`PasswordEncoder` Bean
- `service/UserService.java`：`login` 改为 `passwordEncoder.matches(raw, hash)`
- `sql/init.sql`：`password` 列改 `VARCHAR(100) COMMENT '密码（BCrypt 哈希）'`；8 条种子数据密码改为 BCrypt 密文
- 线上库：`ALTER TABLE t_user MODIFY password VARCHAR(100)...` + `UPDATE t_user SET password='<bcrypt>'`（8 行）

### 设计说明
- 同一明文每次 BCrypt 结果不同（自带随机盐），所以迁移时所有用户统一写入同一个已知密文即可，比对用 `matches`。
- 一次性生成密文的方式（不污染仓库）：用项目 classpath 跑临时 `javac/java`，输出 `$2a$10$...` 并自验 `matches=true`。

### 踩坑点
- **`VARCHAR(50)` 装不下**：BCrypt 输出固定 60 字符，必须扩到 `VARCHAR(100)`（留余量），否则报 `Data too long`。
- **不保留明文兼容**：若为兼容旧数据写「明文匹配成功再升级」的分支，代码里会永久留一块明文逻辑，面试是减分项——故选择一次性迁移。
- **null 短路**：`user == null || !matches(...)`，注意 `||` 顺序，避免用户不存在时对 null 调 `matches`。

### 验证（已实测）
```bash
B=http://localhost:8080
curl -s -X POST $B/api/users/login -H "Content-Type: application/json" -d '{"username":"xiaohong","password":"123456"}'   # → 200 + token
curl -s -X POST $B/api/users/login -H "Content-Type: application/json" -d '{"username":"xiaohong","password":"wrong"}'    # → 401
curl -s -X POST $B/api/users/login -H "Content-Type: application/json" -d '{"username":"nobody","password":"123456"}'     # → 401（无 NPE）
```
结果：正确密码 200，错误密码 401，不存在用户 401。

---

## P0-2 引入认证：X-User-Id → JWT（2026-10-06）

### 问题
所有 Controller 直接读 `@RequestHeader("X-User-Id")`，客户端可随意伪造，等于以任意用户身份操作。

### 方案选型
| 方案 | 结论 |
|---|---|
| **JWT 无状态（采用）** | 面试标配、无状态易扩展；代价是无法主动失效、需引入 jjwt |
| Redis 不透明 token | 契合现有 Redis、可主动失效；代价是每请求一次 Redis 查询 |
| JWT + Redis 黑名单 | 最完整但维护两套，本次未采用 |

### 改动文件
**后端（xhs-backend）**
- `pom.xml`：新增 `jjwt-api/impl/jackson 0.11.5`（版本属性 `jjwt.version`）
- `application.yml`：新增 `xhs.jwt.secret` / `xhs.jwt.expire-minutes`
- `common/JwtUtil.java`【新增】：签发 / 解析 JWT（HS256，subject=userId）
- `common/UserContext.java`【新增】：ThreadLocal 保存当前 userId
- `auth/AuthInterceptor.java`【新增】：解析 `Authorization: Bearer`，写 UserContext；无效 token 返回 HTTP 401
- `vo/LoginVO.java`【新增】：`{ token, user }`
- `config/WebConfig.java`：注册 `AuthInterceptor`（仅 `/api/**`）
- `service/UserService.java`：`login` 返回 `Result<LoginVO>`，成功后签发 token
- `controller/{User,Note,Interact,Comment}Controller.java`：删除 `@RequestHeader("X-User-Id")`，改从 `UserContext.getUserId()` 取

**前端（xhs-frontend）**
- `utils/user.js`：新增 `currentToken()`，`saveUser(user, token)`，`clearUser()` 连带清 token，`isLoggedIn()` 改为看 token
- `api/index.js`：请求头改发 `Authorization: Bearer <token>`；响应 401 时清登录态并提示
- `views/Login.vue`：登录结果取 `res.token` / `res.user`

### 设计说明（关键决策）
1. **拦截器语义是"可选认证"**：不带 header 放行（匿名），交给各接口自行决定是否要求登录；带了但无效则立即 401。
   这样既堵住伪造，又保留原有「读接口公开、写接口需登录」的语义。
2. **不信任 X-User-Id**：全项目已无任何地方读取该请求头，伪造通道彻底关闭。
3. **登录接口无需 exclude**：`/api/users/login` 本来就不带 token，匿名放行即可命中。

### 踩坑点
- **ThreadLocal 必须在 `afterCompletion` 清理**：Tomcat 复用线程，不清会串号——下一个请求可能读到上一个用户的 userId。
- **HS256 密钥 ≥ 32 字节**：`Keys.hmacShaKeyFor` 对短密钥直接抛异常；yml 里的默认 secret 已满足，生产应走环境变量。
- **前端登录响应结构变了**：`data` 从裸 `User` 变成 `{token, user}`，前端 `Login.vue` 必须同步改，否则 token 拿不到。故本次前后端一起改（硬切换，无兼容回退——留回退等于认证可绕过）。

### 验证（已实测）
```bash
B=http://localhost:8080
# 1) 登录拿 token
curl -s -X POST $B/api/users/login -H "Content-Type: application/json" \
     -d '{"username":"xiaohong","password":"123456"}'
# 2) 伪造 X-User-Id → 应 401
curl -s $B/api/notes/follow -H "X-User-Id: 1"
# 3) 无 token → 应 401
curl -s $B/api/notes/follow
# 4) 合法 token → 应 200
curl -s "$B/api/notes/follow?page=1&size=2" -H "Authorization: Bearer <TOKEN>"
```
结果：1) 返回 `{token, user}`；2) `{"code":401}`；3) `{"code":401}`；4) `{"code":200,...}`；
非法 token 返回 HTTP 401；公开读接口无 token 仍 200。

---

## P0-1 库表结构对齐 init.sql（2026-10-06）

### 问题
线上库存在 `t_note_share` 表，但未写进 `sql/init.sql`；换机器 `docker compose up` 会缺表。另 `t_comment` 缺 `(note_id, id)` 联合索引。
同时发现 `t_note.share_count` 列注释为乱码 `'åˆ†äº«æ•°'`（应为 `分享数`）。

### 改动
- `sql/init.sql`：
  - 新增 `t_note_share` 表（列注释 + 表注释 `'分享表'` + `uk_user_note` + `idx_note`），置于收藏表之后，原「评论表/关注表」编号顺延为 6/7
  - `t_comment` 新增 `KEY idx_note_id (note_id, id)`
- 线上库（`xhs-mysql` 容器）同步执行对应 `ALTER`（补表注释/索引、修复乱码注释、加联合索引）

### 踩坑点
- 乱码注释是历史 `ALTER` 时字符集不对导致；DDL 脚本与线上库会双向漂移，需以脚本为唯一真源。
- `t_comment.idx_note` 被新增的 `idx_note_id` 前缀覆盖，属冗余索引，本次按「只增不删」保留。

### 验证
```bash
docker exec xhs-mysql mysql -uroot -p123456 -N -e \
"SELECT table_name,index_name,GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics \
 WHERE table_schema='xhs' GROUP BY table_name,index_name ORDER BY table_name,index_name;"
```
结果：全库 20 条索引与 `init.sql` 声明逐项一致。
