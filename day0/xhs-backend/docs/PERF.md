# 压测数据记录

> 本文分两部分：**一、优化前后对照（2026-10-07 实测，含脚本与原始数据）**；
> **二、Day8 限流场景的历史验证数据**。
> 所有数字都是真实跑出来的，脚本在 [bench/](bench/)，可复现。

---

## 一、优化前后对照（2026-10-07 实测）

### 环境与方法

| 项 | 说明 |
|---|---|
| 基线版本 | `git worktree` 取 `593bffa`（Day1 版：全部直查 MySQL，无 Redis/MQ/ES） |
| 优化版本 | 当前 HEAD（Redis 缓存 + Lua 幂等 + MQ + ES + Feed/热榜 ZSet） |
| 数据集 | **独立压测库 `xhs_bench`**（不动开发库）：500 用户 / 3000 笔记 / 15 万点赞 / 6 万收藏 / 1.5 万评论 |
| 工具 | JMeter 5.6.3，**同一个计划文件**（[bench/bench.jmx](bench/bench.jmx)） |
| 负载 | 50 线程 / ramp-up 5s / 循环 20 → 每轮 1000 请求，**零错误**（1000/1000 全是 200） |
| 差异 | 仅两处：端口不同、认证头不同（基线 `X-User-Id: 5`；优化版 `Authorization: Bearer <jwt>`，同一用户 id=5） |

> ⚠️ 优化版压测期间把限流阈值临时调大（`xhs.rate-limit.*=1000000`），
> 目的是**隔离出读/写逻辑本身的差异**，否则请求会被 429 掉，测的是限流器而不是业务。
> 项目默认阈值见主工程（like=20 / comment=5 / search=10 / default=50 次/秒/用户）。

### 结果

| 场景 | 基线 平均RT | 优化后 平均RT | 变化 | 基线 P95 | 优化后 P95 |
|---|---|---|---|---|---|
| 笔记详情 `GET /api/notes/{id}`（1 篇） | 6.5 ms | 9.9 ms | **+52%** | 11.0 ms | 14.0 ms |
| 推荐页 `GET /api/notes/list`（10 篇） | 6.8 ms | 43.4 ms | **+538%** | 9.0 ms | 53.0 ms |
| 热榜 `GET /api/notes/hot`（10 篇） | 9.5 ms | 44.7 ms | **+370%** | 12.0 ms | 52.0 ms |
| 搜索 `GET /api/notes/search`（20 条） | 6.9 ms | 134.1 ms | **+1843%** | 9.0 ms | 164.0 ms |
| 点赞 `POST /api/notes/{id}/like` | 3.6 ms | 5.3 ms | +47% | 6.0 ms | 7.0 ms |

### 结论（诚实版）

**在这个数据量与并发下，"优化后"的读接口反而更慢。** 这不是测错了，原因清楚：

1. **主因是读路径上的 N+1 Redis 往返**。优化版的 `NoteService` 在返回列表前会对**每一篇**笔记做：
   - `fillStatus`：2 次 `SISMEMBER`（是否已赞 / 已收藏）
   - `mergeCounts`：3 次 `GET`（点赞数 / 收藏数 / 分享数）

   即**每篇笔记 5 次 Redis 往返**，10 篇列表就是 **50 次往返**；而基线只有 **1 条 SQL**。

2. **数据自证**：`detail` 只处理 1 篇 → 慢 3.4 ms；`list` 处理 10 篇 → 慢 36.6 ms。
   差值 ≈ 3.7 ms/篇，恰与「每篇 5 次 Redis 往返（约 0.7 ms/次）」吻合。**瓶颈就是往返次数，不是 Redis 本身。**

3. **缓存只有在"回源真的慢"时才划算**。基线是 3000 行的单机 MySQL、buffer pool 全命中：
   按主键查详情、简单联表、甚至热榜的聚合都只要几毫秒。给一个本来 6 ms 的查询加 50 次网络往返，
   必然变慢。**"缓存 = 更快"是个常见误解。**

4. 搜索慢 134 ms 除 N+1 外，还有 ES 查询本身的开销（容器堆内存仅 512 MB、IK 分词、`search_after` 排序）；
   这一项需要单独优化，不能只看"用了 ES"。

### 这份数据的价值

它**证明了 TODO 里 P3-1（消除 N+1 Redis 往返）是当前第一优先级的性能项**——
不是纸面分析，是实测出来的回归。修法很直接：`SISMEMBER`×N 和 `GET`×N 改成
**pipeline / `MGET` / `SMISMEMBER`** 批量一次往返（预计 list 场景能从 43 ms 回到个位数）。

> 🚫 **不要在简历上写"QPS 提升 N 倍"**。当前实测是**优化后更慢**；
> 真实结论是"定位到并量化了一个性能回归"。修完 N+1 再复测，才有资格谈提升。

### 复现

```bash
# 1) 造压测库（独立库，不动开发数据）
docker exec -i xhs-mysql mysql -uroot -p123456 --default-character-set=utf8mb4 < docs/bench/gen_bench.sql

# 2) 分别取基线与优化版源码
git worktree add ../xhs-baseline-bench 593bffa      # 需给它的 pom 补 UTF-8 源码编码（本机默认 GBK 编不过）
git worktree add ../xhs-optimized-bench HEAD

# 3) 两者的 application.yml 都指向 xhs_bench；基线端口 8082、优化版 8081
#    优化版另需把 xhs.rate-limit.* 调大以隔离限流
# 4) 清空 ES 索引与 Redis，让优化版按压测库重建（回填/对账/热榜重算任务）

# 5) 压测
cd docs/bench && ./run-bench.sh        # 5 场景 × 2 版本，结果写入 out-*.jtl
```

---

## 二、Day8 限流场景的历史验证数据（2026-10-06）

### 已有数据

采集时间：2026-10-06（**早于** P1-13 的"按用户维度限流"改造，当时是**全局固定窗口**）
工具：JMeter 5.6.3　脚本与原始结果：`day8/ratelimit/`

| 场景 | 样本数 | 错误数 | 平均 RT | P95 | 最大 RT |
|---|---|---|---|---|---|
| `POST /api/notes/1/like`（限流压测） | 15000 | **12000** | 51.6 ms | 149.0 ms | 585 ms |
| `GET /api/notes/search`（同轮） | 600 | 0 | 62.4 ms | 264.7 ms | 436 ms |
| `POST /api/notes/1/comments`（评论限流） | 4000 | **3600** | 77.1 ms | 658.6 ms | 1742 ms |
| `GET /api/notes/search`（评论轮） | 600 | 0 | 87.9 ms | 413.4 ms | 1152 ms |

原始文件：`day8/ratelimit/report/statistics.json`、`day8/ratelimit/report-comments/statistics.json`

**结论**：超限请求被限流组件以 429 拦下（点赞 75% 被拒、评论 90% 被拒），
同级未被限流的搜索接口错误率为 0 —— 证明限流**只作用于目标接口、没有连坐**。
注意这里的"错误数"是**预期的 429**，不是故障。

---

### 采集环境注意

> 下面这几点对第一部分（优化前后对照）同样适用。

- 中间件跑在 Docker（MySQL 3307 / Redis 6380 / RabbitMQ 5672 / ES 9200），单机。
- 应用与压测工具同机，**QPS 绝对值不能代表生产容量**。
- 报告中的 RT 含限流拦截器的开销（那是被限流请求的 RT，不是正常请求的 RT）。
