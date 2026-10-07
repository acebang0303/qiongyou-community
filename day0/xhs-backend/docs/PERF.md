# 压测数据记录

> ⚠️ **先说清楚**：仓库里**只有 Day8 限流场景的验证数据**，**没有"优化前 vs 优化后"的对照数据**。
> 本文如实记录已有数据、明确缺口，并给出可复现的采集步骤。**不要把没有测过的数字写进简历。**

---

## 一、已有数据：Day8 限流验证

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

## 二、缺口：优化前后的对照数据（尚无）

简历/面试里最有说服力的是这类对照：**同一个接口，优化前后 QPS / 平均 RT / P95 / MySQL 压力**。
目前仓库里**没有**，原因：历次压测都是在"改完之后"跑的验证型压测，没有留基线。

### 建议补测的对照（按面试价值排序）

| # | 对照 | 基线（优化前） | 优化后 | 预期观察 |
|---|---|---|---|---|
| 1 | 笔记详情 `GET /api/notes/{id}` | 直查 MySQL 联表 | Redis 缓存 + 空对象 + 分布式锁 | QPS↑、RT↓、`==> Preparing:` 次数≈0 |
| 2 | 点赞 `POST /api/notes/{id}/like` | 同步 4 条 SQL | Redis Lua + MQ 异步落库 | RT↓为主（写被削峰）、MySQL 写入变匀速 |
| 3 | Feed `GET /api/notes/follow` | 子查询联表排序 | ZSet 收件箱 | RT↓、MySQL 压力↓ |
| 4 | 热榜 `GET /api/notes/hot` | 全表聚合排序 | ZSet `ZREVRANGE` | RT↓ 明显 |
| 5 | 搜索 `GET /api/notes/search` | `LIKE '%kw%'` 全表扫 | ES 倒排 + IK | 命中质量↑、MySQL 压力≈0 |

### 复现步骤

```bash
# 1) 用 git worktree 取出"优化前"的版本，避免污染当前工作区
git worktree add ../baseline <基线commit>
cd ../baseline/day0/xhs-backend && mvn spring-boot:run     # 起在 8080

# 2) JMeter 压基线（线程数/持续时间两版必须一致）
jmeter -n -t plan.jmx -l baseline.jtl -e -o report-baseline

# 3) 回主工程，起优化后版本，用同一个 plan 再压一次
jmeter -n -t plan.jmx -l optimized.jtl -e -o report-optimized

# 4) 取两次的 statistics.json 对比 QPS/平均 RT/P95；MySQL 侧同时观察
docker exec xhs-mysql mysql -uroot -p123456 -e "SHOW GLOBAL STATUS LIKE 'Com_insert';"
```

**关键纪律**：
- 两轮压测的**线程数、ramp-up、循环次数、数据量**必须完全一致，否则数字不可比。
- 每轮开始前清缓存/复位数据（否则第二轮跑在热缓存上）。
- 压测机与被压服务在同一台机器时，**测试工具自身会抢资源**，QPS 绝对值仅供参考，
  **看相对变化**即可。
- 这台机器上 `ab`/`wrk`/`hey` 都没装，**JMeter 5.6.3 可用**：
  `jmeter -n -t plan.jmx -l result.jtl -e -o report`。

---

## 三、注意：这些数据的采集环境

- 中间件跑在 Docker（MySQL 3307 / Redis 6380 / RabbitMQ 5672 / ES 9200），单机。
- 应用与压测工具同机，**QPS 绝对值不能代表生产容量**。
- 报告中的 RT 含限流拦截器的开销（那是被限流请求的 RT，不是正常请求的 RT）。
