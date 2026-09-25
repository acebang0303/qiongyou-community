# Day8 实训手册：Elasticsearch 搜索 + 限流与降级

## 一、今日任务目标

| 目标 | 说明 |
| --- | --- |
| 理解倒排索引 | 为什么搜索不该用 MySQL LIKE |
| 认识限流算法家族 | 固定窗口/滑动窗口/漏桶/令牌桶的原理与取舍 |
| 理解降级与熔断 | 可用性优先思想，掌握兜底返回的设计 |
| 完成 ES 数据同步 | 发布笔记 → MQ → ES 索引 |
| 完成搜索切换 | 搜索走 ES，异常时回退 MySQL |
| 实现限流 | Redis 固定窗口：超限返回 429 |
| 实现降级 | 热榜服务异常时兜底返回最新笔记 |
| 课堂实战 | 给评论接口加限流、给关注 Feed 加降级（第八节，课内启动、课下完成） |

## 二、问题场景（先复现，再优化）

本课的三个痛点都藏在“看起来能跑”的基线代码里。优化的第一步不是急着写新代码，而是先把问题**亲手复现**出来、把优化前的指标**如实记录**下来——没有基线，就无法证明优化的价值，也无法在面试里讲清“为什么要上 ES / 限流 / 降级”。

> **术语先行**
>
> - **全表扫描**：MySQL 找不到可用索引时，只能一行一行地把整张表读完再过滤。“扫一次全表 = 把所有笔记从头读一遍”，数据量越大越慢。
>
> - **QPS**：Queries Per Second，每秒请求数，衡量系统吞吐能力的核心指标。
>
> - **RT**：Response Time，响应时间，单个请求从发出到拿到结果的耗时，常用平均值 / P99 衡量。
>
> - **自我保护**：系统在过载时主动拒绝或退化一部分请求，用“局部可用”换“整体不崩”的能力。限流、降级、熔断都属于自我保护。

### 问题 1：搜索走 `LIKE '%关键词%'`，全表扫描

基线的搜索接口 `GET /api/notes/search?keyword=三亚`，底层是一条对 `title / content / tags` 三个字段做 `LIKE CONCAT('%', 关键词, '%')` 的 SQL。以 `%` 开头的模糊匹配无法使用 B+ 树索引（原理见 3.1），每搜一次就把 `t_note` 全表扫一遍。笔记量涨到几十万时，单次搜索 RT 会飙到几百毫秒，并发一高，MySQL 的 CPU 和连接池会瞬间被打满。

**复现方式**：JMeter 以 200~300 并发循环压 `search?keyword=三亚`，观察聚合报告里平均 RT 持续偏高；同时在 MySQL 里执行 `SHOW FULL PROCESSLIST;`，能看到一堆卡在 `LIKE` 全表扫描上的慢查询。

### 问题 2：流量翻倍时，系统没有任何自我保护

热点事件（某篇笔记突然爆了、被大 V 转发）会带来平时数倍甚至 10 倍的瞬时流量。基线代码对所有请求“来者不拒”——请求全部涌向 MySQL 和 Redis，线程池耗尽、连接池打满，最终**整个系统被拖垮**，连原本正常的用户也访问不了。这就是缺少限流的后果：一个接口的过载会连坐全站。

**复现方式**：JMeter 把点赞接口 `POST /api/notes/{id}/like` 的并发拉到远超设计容量（如 500+ 并发持续压），观察 RT 陡增、错误率上升、后端工作线程被占满。

### 问题 3：热榜强依赖 Redis，Redis 一抖动首页就崩

首页热榜接口 `GET /api/notes/hot` 的数据来自 Redis 的 ZSet。基线代码没有做任何异常兜底——一旦 Redis 抖动或宕机，`hot()` 直接抛异常，接口返回 **500**，前端**整个首页热榜区域白屏**。一个本属“锦上添花”的非核心功能，拖垮了整个页面的可用性。

**复现方式**：执行 `docker stop xhs-redis`，再刷新首页热榜，观察接口返回 500、前端热榜区域空白无数据。

### 基线数据记录表（优化前必填）

先把下表填满，作为“优化前”基线存档；等第六节压测完成后，再回来填“优化后”一列做对比：

| 复现场景 | 复现操作 | 优化前现象 / 指标 |
| --- | --- | --- |
| 搜索全表扫描 | 200~300 并发压 `search?keyword=三亚` | 平均 RT = ____ ms；MySQL 慢查询堆积 |
| 无自我保护 | 超容量压 `POST /api/notes/{id}/like` | RT 陡增、错误率 ____%、线程池打满 |
| 热榜强依赖 Redis | `docker stop xhs-redis` 后刷新热榜 | 热榜接口返回 ____（500），首页白屏 |

> 记住这三个数字。今天做完优化后，同样三张压测表再跑一遍，你会亲眼看到：搜索 RT 断崖下降、超限请求被 `429` 拦在门外、Redis 挂了首页依然有内容——这就是本课要交付的价值。

## 三、理论基础：搜索引擎与流量保护体系

### 3.1 为什么 LIKE 搜索扛不住：B+ 树的局限

```text
MySQL 的 B+ 树索引只对“最左前缀”有效：
  WHERE title LIKE '海南%'   → 能走索引 ✅
  WHERE title LIKE '%海南%'  → 无法定位起点 → 全表扫描 ❌（本课基线就是这种）
```

后果：每搜一次扫全部笔记，数据量越大越慢，且无法按相关性排序。

### 3.2 倒排索引：搜索引擎的核心思想（重点）

正排索引（MySQL 思路）：文档 → 内容（找包含某词的文档要全部翻一遍）

倒排索引（ES 思路）：**词 → 文档ID列表**，写入时就把“字典”建好：

```text
分词后的倒排索引（Term Dictionary）：
  "三亚"   → [1, 5, 9]
  "海南"   → [1, 2, 5, 7, 9]
  "环岛"   → [3, 5]
搜索"海南" → 直接命中 [1,2,5,7,9]，无需扫描任何文档全文，且可算相关性排序（TF/IDF）
```

> 分词（了解）：本课用默认 standard 分词器；中文生产环境用 IK 分词器，它有两种模式：`ik_smart`（粗粒度，搜索用）/ `ik_max_word`（细粒度，建索引用）。

### 3.3 ES 核心概念、原理与 MySQL 对照 + 动手（上课必做）

#### 3.3.1 Elasticsearch 是什么（概念与用途）

Elasticsearch（简称 ES）是一个基于 **Lucene** 的分布式**搜索与分析引擎**，通过 RESTful API 对外提供服务。你可以把它理解成“一个专门为搜索和统计而生的、可横向扩展的数据库副本”——它不替代 MySQL，而是把 MySQL 里需要被“全文检索 / 聚合分析”的数据同步一份过来，用倒排索引换取极致的搜索性能。

它的典型用途：

| 用途场景 | 说明 | 例子 |
| --- | --- | --- |
| **全文搜索** | 按关键词模糊检索，支持分词、相关性排序、高亮 | 本课的笔记搜索、电商商品搜索 |
| **日志分析（ELK）** | Elasticsearch + Logstash + Kibana，收集并检索海量日志 | 排查线上问题时按关键字搜日志 |
| **聚合统计** | 对海量数据做分组、计数、求和等分析（aggregation） | 热门话题统计、用户行为分析 |
| **自动补全 / 推荐** | 输入联想、拼写纠错、more-like-this | 搜索框下拉提示 |

#### 3.3.2 ES 的四个核心原理（为什么它快）

> **近实时（NRT, Near Real-Time）**
>
> ES 写入文档后**不是立刻**可搜，而是先放进内存 buffer，默认每 **1 秒** refresh 一次生成新的段（segment）后才可被搜到。所以叫“近实时”而非“实时”，这也是“发布笔记后隔一两秒才搜得到”的原因。（动手环节我们会用 `?refresh=true` 强制立即可见。）

- **倒排索引**：见 3.2，写入时就把“词 → 文档ID”的字典建好，搜索时直接查字典而非扫全文，这是快的根本原因。
- **RESTful + JSON**：所有操作（建索引、写文档、搜索）都是 HTTP 请求 + JSON 报文，无需专用客户端。这正是本课用 `RestTemplate` 直连 `http://localhost:9200`、你也能用 curl 对照验证的原因。
- **分布式分片与副本**：一个索引可拆成多个 **主分片（primary shard）** 分散到不同节点并行存储与检索（横向扩展），每个主分片还可有 **副本分片（replica shard）** 提供高可用与读吞吐。本课是单节点（`discovery.type=single-node`），默认 1 主分片 1 副本即可，了解概念即可。
- **Mapping（映射）**：定义每个字段的类型（text / keyword / long…）与分词方式，相当于 MySQL 的表结构（schema）。ES 也支持“动态映射”（不定义就自动猜类型），但生产环境建议显式定义，避免类型猜错。

#### 3.3.3 核心概念与 MySQL 对照

| MySQL | Elasticsearch | 说明 |
| --- | --- | --- |
| 数据库 database | 索引 index | 本课：`xhs_notes` |
| 表 table | 类型 type（7.x 后废弃） | 一个索引即一类文档 |
| 行 row | 文档 document（JSON） | 一篇笔记 = 一个文档 |
| 列 column | 字段 field | title / content / tags |
| schema | mapping | 字段类型与分词定义 |
| SQL | Query DSL（JSON 查询语句） | 如 `multi_match`、`term`、`bool` |
| 主键 primary key | 文档 ID（`_id`） | 唯一标识，按 ID 覆盖写天然幂等 |

> 补充两个高频术语：**分片 shard**（索引的水平切分单元，决定扩展能力）、**分析器 analyzer**（把文本切成词的组件，中文常用 IK）。

#### 3.3.4 动手：建索引 → 灌数据 → 搜一下（上课必做）

**关键认知**：ES 装好后是**空的**——既没有 `xhs_notes` 索引，也没有任何文档，直接 `_search` 会报 `index_not_found_exception`。所以必须**先建索引、再灌数据，才能查询**。下面三步走一遍，你会立刻理解“索引 / 文档 / 搜索”到底长什么样。

**第 1 步：创建索引并定义 mapping**（三个 text 字段，与代码 `EsService.ensureIndex()` 一致）

> 若已启动过 Spring Boot 应用，`EsInitRunner` 会自动建好索引，本步可跳过。

mapping 定义已存为 `mapping.json`（随手册放在 `for faculty/docs/`）。**先 `cd` 到该目录**再执行（cmd / PowerShell 通用，统一用 `curl.exe`）：

```bash
curl.exe -X PUT "http://localhost:9200/xhs_notes" -H "Content-Type: application/json" --data-binary "@mapping.json"
```

`mapping.json` 内容（三个字段都设为 `text`，可分词搜索）：

```json
{
  "mappings": {
    "properties": {
      "title":   { "type": "text" },
      "content": { "type": "text" },
      "tags":    { "type": "text" }
    }
  }
}
```

**第 2 步：用 `_bulk` 批量灌入示例笔记**（`?refresh=true` 让数据立即可搜，否则要等约 1 秒的 NRT 刷新）

数据文件 `notes.ndjson` 同样在 `for faculty/docs/` 目录（UTF-8 无 BOM，5 篇示例笔记）。在同一目录执行（cmd / PowerShell 通用）：

```bash
curl.exe -X POST "http://localhost:9200/xhs_notes/_bulk?refresh=true" -H "Content-Type: application/json" --data-binary "@notes.ndjson"
```

`notes.ndjson` 内容如下（万一文件没下发，可照此自建：每行一条、存 UTF-8 无 BOM、末尾留一个空行）：

```json
{"index":{"_id":"1"}}
{"title":"三亚海边度假全攻略","content":"大东海的沙滩和日落绝美","tags":"三亚,海南,度假"}
{"index":{"_id":"2"}}
{"title":"海南自由行避雷清单","content":"别在景区门口打车","tags":"海南,自由行"}
{"index":{"_id":"3"}}
{"title":"环岛骑行三日记","content":"沿海公路骑行看日落与灯塔","tags":"环岛,骑行"}
{"index":{"_id":"5"}}
{"title":"三亚环岛自驾","content":"从三亚出发一路向北","tags":"三亚,海南,环岛"}
{"index":{"_id":"9"}}
{"title":"三亚拍照圣地TOP10","content":"随手一拍都是大片","tags":"三亚,海南,拍照"}
```

> **Git Bash / Linux / macOS 用户**：把 `curl.exe` 换成 `curl`、`--data-binary "@文件"` 换成 `--data-binary @文件` 即可，其余一致。
>
> **两个必踩的坑**：
> - `_bulk` 必须用 `--data-binary @文件`，**不能用 `-d @文件`**——`-d` 会删掉换行，NDJSON 挤成一行，ES 报 `malformed action/metadata line`。
> - 原手册那条 `-d '{多行}'` 为何在 Windows 报错：cmd 不认单引号当字符串定界符、命令也不能中间换行；PowerShell 里 `curl` 还是 `Invoke-WebRequest` 的别名。改成“数据文件 + `curl.exe` + `--data-binary`”后，cmd / PowerShell 都能跑，中文也不乱码。

**第 3 步：验证与搜索**

无中文的命令，`curl.exe` 直接跑（cmd / PowerShell 通用）：

```bash
curl.exe "http://localhost:9200/_cat/indices?v"     # 应看到 xhs_notes 索引
curl.exe "http://localhost:9200/xhs_notes/_count"   # 应返回 count: 5
```

搜“三亚”带中文，**最省事是直接用浏览器**打开下面地址（浏览器自动做 UTF-8 URL 编码，命中 1、5、9）：

```text
http://localhost:9200/xhs_notes/_search?q=三亚&pretty
```

**PowerShell 一条龙备选**（不想用数据文件时，整段复制进 PowerShell 执行）：

```powershell
# 建索引
Invoke-RestMethod -Uri "http://localhost:9200/xhs_notes" -Method Put -ContentType "application/json" -Body '{"mappings":{"properties":{"title":{"type":"text"},"content":{"type":"text"},"tags":{"type":"text"}}}}'

# 灌数据：here-string 原样保留换行；UTF8 字节避免中文乱码
$bulk = @'
{"index":{"_id":"1"}}
{"title":"三亚海边度假全攻略","content":"大东海的沙滩和日落绝美","tags":"三亚,海南,度假"}
{"index":{"_id":"2"}}
{"title":"海南自由行避雷清单","content":"别在景区门口打车","tags":"海南,自由行"}
{"index":{"_id":"3"}}
{"title":"环岛骑行三日记","content":"沿海公路骑行看日落与灯塔","tags":"环岛,骑行"}
{"index":{"_id":"5"}}
{"title":"三亚环岛自驾","content":"从三亚出发一路向北","tags":"三亚,海南,环岛"}
{"index":{"_id":"9"}}
{"title":"三亚拍照圣地TOP10","content":"随手一拍都是大片","tags":"三亚,海南,拍照"}
'@
Invoke-RestMethod -Uri "http://localhost:9200/xhs_notes/_bulk?refresh=true" -Method Post -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($bulk))

# 搜索验证
Invoke-RestMethod -Uri "http://localhost:9200/xhs_notes/_search?q=三亚"
```

> **为什么搜“三亚”能命中却不够精准**：本课用默认 `standard` 分词器，它把中文按**单字**切分（“三亚” → “三”“亚”），所以是“含‘三’或‘亚’即命中”。够用但不精确——生产环境要用 IK 分词器按词切分（呼应 3.2）。

> 课堂讨论：ES 既然这么强，能不能取代 MySQL？
>
> （不能。ES 不支持事务、不擅长频繁更新、是近实时而非实时；它是“搜索专用副本”，数据的最终归宿仍是 MySQL——所以本课用 MQ 异步同步，而非双写。）
>
> 延伸：刚才手工灌的 5 篇是**演示数据**。真实系统里 ES 的数据来自“发布笔记 → MQ → EsConsumer 写入”（见第五节）；而 MySQL 里已存在的存量笔记不会自动进 ES，需要一次性回填（bulk 导入），详见 6.1。

### 3.4 限流算法四大家族（面试常考）

> **术语先行**
>
> - **限流**：给接口设一个“每秒最多处理 N 个请求”的闸门，超出的直接拒绝（返回 429），用“拒绝一部分”保护“系统不被压垮”。
>
> - **阈值 / QPS 上限**：闸门允许的每秒最大请求数，如本课点赞接口 1000/秒、搜索 500/秒。

先看四大家族的对比总览，再逐个展开原理与伪代码：

| 算法 | 原理 | 优点 | 缺点 | 本课 |
| --- | --- | --- | --- | --- |
| **固定窗口** | 每秒一个计数器，超阈拒绝 | 实现最简单 | **窗口边界可能瞬间通过 2 倍流量** | **✅** |
| 滑动窗口 | 把窗口细分成小格滚动统计 | 平滑，解决边界突刺 | 实现稍复杂 | 进阶 |
| 漏桶 | 请求入桶，匀速流出 | 绝对平滑 | 无法应对合理突发 | ❌ |
| 令牌桶 | 匀速发令牌，有令牌才放行 | 允许一定突发（桶里攒令牌） | 实现复杂 | 进阶（Redisson 内置） |

#### 3.4.1 固定窗口计数（Fixed Window）——本课落地

**原理**：把时间切成一格一格固定的窗口（如每 1 秒一格），每格维护一个计数器。请求进来就 `计数 +1`，只要当前窗口计数没超过阈值就放行，超过就拒绝；到下一个窗口计数清零重新来。

```text
时间轴（阈值 = 1000/秒）：
  [00.0s ────── 01.0s) [01.0s ────── 02.0s)
   计数 0 → 1000          计数清零 → 重新计
```

**伪代码**（正是本课 `RateLimitInterceptor` 的逻辑）：

```text
key = "rate:limit:" + 接口 + ":" + 当前秒数      # 每秒一个独立计数 Key
count = INCR(key)                                # 原子自增，返回自增后的值
if count == 1:  EXPIRE(key, 2 秒)                 # 第一次写入才设过期，避免 Key 残留
if count > 阈值:  返回 429（拒绝）
else:            放行
```

**优点**：实现极简单，一个 `INCR` + 一个 `EXPIRE` 就搞定，Redis 天然支持。
**致命缺点——窗口边界突刺**：假设阈值 1000/秒，如果在第 1 秒的最后 0.1 秒打满 1000，紧接着第 2 秒的最前 0.1 秒又打满 1000，那么在这**相邻的 0.2 秒内实际通过了 2000 个请求**，是阈值的 2 倍。因为计数器在窗口切换时“一刀清零”，管不住跨窗口的瞬时叠加。

#### 3.4.2 滑动窗口（Sliding Window）——解决突刺

**原理**：不再“整秒清零”，而是把 1 秒窗口细分成 N 个小格（如 10 个 100ms 的小格），每格单独计数。统计时把“当前时刻往前 1 秒”覆盖的所有小格加起来，随时间**平滑滚动**。这样边界处不会突变，突刺被摊平。

```text
把 1 秒切成 10 个 100ms 小格，窗口随时间右滑：
  |100|100|100|100|100|100|100|100|100|100|
  └─────── 始终只统计最近 10 格之和 ───────┘
```

**伪代码**：

```text
把窗口切成 N 个小格，每格一个计数
当前窗口计数 = 最近 N 个小格计数之和
if 当前窗口计数 > 阈值:  拒绝
else:  当前小格计数 +1，放行
每隔一小格时间：丢弃最旧的一格、追加新的一格
```

**取舍**：平滑、无边界突刺；代价是要维护多个小格、实现稍复杂。Sentinel 的滑动窗口就是典型实现（用 LeapArray 环形数组存小格）。

#### 3.4.3 漏桶（Leaky Bucket）——绝对匀速

**原理**：把请求想象成水倒进一个底部漏水的桶。桶以**固定速率**漏水（处理请求），桶满则溢出（拒绝）。无论进水多猛，出水永远匀速——它强制平滑输出速率。

```text
请求 →→→ [ 漏桶 (容量=桶大小) ] →→→ 匀速流出（固定速率处理）
          桶满则新请求被丢弃/排队
```

**伪代码**：

```text
每个请求到来：
  先按“距上次漏水的时间 × 漏水速率”扣减桶中水量
  if 桶中水量 < 桶容量:   放入桶（水量 +1），排队等待匀速处理
  else:                   拒绝（溢出）
```

**取舍**：输出绝对平滑，适合保护下游“只能匀速消化”的场景（如调用第三方限速 API）；**缺点是无法应对合理的突发流量**——哪怕系统很闲，也只能匀速放行，桶里攒不下“额度”。

#### 3.4.4 令牌桶（Token Bucket）——允许突发（最常用）

**原理**：以**固定速率**往桶里放令牌，桶有容量上限。请求来了必须**拿到一个令牌**才能通过，没令牌就拒绝。空闲时令牌会在桶里**攒着**（最多攒满桶容量），因此当突发流量来临时，可以一次性把攒下的令牌用掉——**既限制平均速率，又允许一定突发**。这是实际应用最广的算法。

```text
匀速发令牌 ↓
        [ 令牌桶 (容量=可攒的最大令牌数) ]
请求 →→  取到令牌才放行；桶空则拒绝
```

**伪代码**：

```text
每个请求到来：
  先按“距上次的时间 × 发令牌速率”补充令牌（不超过桶容量）
  if 桶中令牌 ≥ 1:   取走 1 个令牌，放行
  else:              拒绝
```

**令牌桶 vs 漏桶**（面试高频对比）：漏桶控制的是**流出速率**（绝对匀速、不许突发）；令牌桶控制的是**流入速率**（允许桶里攒令牌、可应对突发）。所以令牌桶更贴合真实业务。Redisson 的 `RRateLimiter`、Guava 的 `RateLimiter` 都是令牌桶实现。

> 课堂实验：固定窗口的边界突刺——在第 0.9 秒和第 1.1 秒各打满阈值，相当于 0.2 秒内通过了 2 倍阈值的流量。思考：滑动窗口 / 令牌桶分别怎么解决？
>
> 限流粒度认知：本课是“全局窗口”（所有用户共享阈值）；生产上常按用户维度限流（防单个羊毛党），Key 变为 `rate:limit:{user}:{接口}:{秒}`。

### 3.5 降级与熔断：可用性优先的最后防线（重点）

设计原则：**宁可给一个“够用”的结果，也不给一个报错的页面。**

| 概念 | 含义 | 本课案例 |
| --- | --- | --- |
| **降级** | 主动放弃非核心功能，保核心链路 | 搜索回退 MySQL；热榜返回最新列表 |
| **熔断** | 下游持续失败 → 暂时直接短路，快速失败 | 电路保险丝类比：跳闸→冷却→试送电 |
| 兜底数据 | 降级时返回的替代内容 | 静态页、默认值、本地缓存 |
| 读写分离降级 | 写不可用 → 关闭写入保读；读不可用 → 展示缓存旧数据 | 大促时常见操作 |
| 熔断器三态 | 关闭（正常放行）→ 打开（直接拒绝）→ 半开（试探恢复） | 了解即可（Sentinel/Resilience4j 实现） |

> 总结本课的三级防护体系：限流（拦住过量流量）→ 降级（部分功能退化但可用）→ 兜底数据（用户永远有响应）。
> 进阶了解：Sentinel / Resilience4j / Hystrix（已停维护）。

## 四、方案设计

### 4.1 Elasticsearch 搜索

```text
写入链路：发布笔记 → 发 NoteEvent 到 note.es.queue → EsConsumer → 写入 ES 索引
查询链路：搜索关键词 → ES multi_match(title/content/tags) → 拿ID → 回查缓存组装
降级链路：ES 异常 → 自动回退 MySQL LIKE（功能可用，性能降级）
```

| 职责 | MySQL | Elasticsearch |
| --- | --- | --- |
| 定位 | 数据最终归宿 | 搜索专用副本 |
| 一致性 | 强一致 | 最终一致（秒级延迟） |

### 4.2 限流（固定窗口）

```text
每次请求：INCR rate:limit:{接口} ，首次设置1秒过期
  计数 ≤ 阈值 → 放行
  计数 > 阈值 → 返回 429 Too Many Requests

阈值：点赞接口 1000/秒，搜索接口 500/秒，其他 2000/秒
```

### 4.3 降级

```text
热榜接口：
  try   → Redis 热榜
  catch → 记录日志 + 返回最新笔记列表（兜底）
```

## 五、编码实现

本节按 `code/day8/` 目录逐个文件说明“它是干什么的、关键逻辑在哪”。先给全景表，再逐个拆解。所有代码都在 Day7 基础上增量修改，包名与目标位置见 `code/day8/README.md`。

### 5.0 文件清单与职责总览

| 序号 | 文件 | 操作 | 主要作用 |
| --- | --- | --- | --- |
| 1 | `dto/NoteEvent.java` | 【新增】 | 笔记事件消息体，MQ 里传递“要索引哪篇笔记” |
| 2 | `service/EsService.java` | 【新增】 | 用 RestTemplate 封装 ES 的三个 REST 操作：建索引 / 写文档 / 搜索 |
| 3 | `service/EsInitRunner.java` | 【新增】 | 应用启动时自动建索引（CommandLineRunner） |
| 4 | `consumer/EsConsumer.java` | 【新增】 | 监听 `note.es.queue`，把笔记写入 ES |
| 5 | `ratelimit/RateLimitInterceptor.java` | 【新增】 | Redis 固定窗口限流拦截器，超限返回 429 |
| 6 | `config/WebConfig.java` | 【新增】 | 注册限流拦截器 + 提供 RestTemplate Bean |
| 7 | `service/NoteService.java` | 【替换】 | search 优先走 ES、异常回退 MySQL；publish 发 ES 消息 |
| 8 | `controller/NoteController.java` | 【替换】 | hot 接口 try/catch 降级，异常时返回最新笔记 |

> 数据流一图看懂：**发布** → `NoteService.publish` 发 `NoteEvent` → MQ → `EsConsumer` → `EsService.indexNote` 写入 ES；**搜索** → `NoteService.search` → `EsService.searchIds`（异常则回退 `noteMapper.selectSearch`）。

### 5.1 NoteEvent.java —— 消息体（为什么只放一个 noteId）

**作用**：MQ 里传输的“笔记索引事件”。发布笔记成功后投递到交换机，由消费者异步建索引，让发布主流程不必等待 ES（削峰 + 解耦，呼应 Day6）。

**关键设计**：消息体**只带 `noteId`**，不带笔记全文。消费者拿到 ID 后回查数据库取最新内容再写 ES——这样即使消息重复投递，写进 ES 的也总是库里的最新值，天然幂等。

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteEvent {
    private Long noteId;   // 只传 ID，消费端回查库取最新内容，保证幂等
}
```

### 5.2 EsService.java —— ES 的 REST 封装（本课核心）

**作用**：不引入 ES 官方客户端，直接用 `RestTemplate` 调 `http://localhost:9200` 的 REST API，方便你用 curl / Postman 一条条对照理解（呼应 3.3.2 “RESTful + JSON”）。它封装三个方法：

| 方法 | 作用 | 对应 REST 请求 |
| --- | --- | --- |
| `ensureIndex()` | 索引不存在则创建（带 mapping） | `GET /xhs_notes` 探测 → `PUT /xhs_notes` |
| `indexNote(note)` | 写入/更新一篇笔记文档 | `PUT /xhs_notes/_doc/{id}` |
| `searchIds(kw,page,size)` | 关键词搜索，返回命中的笔记 ID 列表 | `POST /xhs_notes/_search` |

`ensureIndex()` 的关键：先发 GET 探测索引是否存在，捕获 `404 NotFound` 时才 PUT 建索引；连不上 ES 时**只告警不抛异常**，把“ES 不可用”留给搜索环节降级处理。

```java
public void ensureIndex() {
    try {
        restTemplate.getForEntity(ES_BASE + "/" + INDEX, String.class); // 200=已存在
    } catch (HttpClientErrorException.NotFound e) {                     // 404=不存在→建
        String mapping = "{\"mappings\":{\"properties\":{"
                + "\"title\":{\"type\":\"text\"},"
                + "\"content\":{\"type\":\"text\"},"
                + "\"tags\":{\"type\":\"text\"}}}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(ES_BASE + "/" + INDEX, HttpMethod.PUT,
                new HttpEntity<>(mapping, headers), String.class);
    } catch (Exception e) {
        log.warn("ES 连接失败，搜索将回退到 MySQL：{}", e.getMessage()); // 不抛，留给降级
    }
}
```

`searchIds()` 的关键：用 `multi_match` 同时在 title/content/tags 三个字段里搜，`from/size` 做分页；解析返回 JSON 的 `hits.hits[]._id` 拿到命中笔记的 ID 列表（已按相关性排好序）。

```java
match.put("query", keyword);
match.put("fields", new String[]{"title", "content", "tags"}); // 三字段一起搜
query.put("multi_match", match);
body.put("from", (page - 1) * size);   // 分页起点
body.put("size", size);                // 每页条数
// …POST _search 后，遍历 hits.hits 取每个 _id
```

`indexNote()` 用 `PUT /xhs_notes/_doc/{id}`：**相同 ID 覆盖写**，所以重复消费同一条消息不会写出重复文档（再次呼应幂等）。

### 5.3 EsInitRunner.java —— 启动时自动建索引

**作用**：实现 Spring Boot 的 `CommandLineRunner`，应用启动完成后自动执行一次 `esService.ensureIndex()`，保证服务一起来索引就存在（不存在才建、已存在跳过），省去手动建索引。

```java
@Component
public class EsInitRunner implements CommandLineRunner {
    @Autowired private EsService esService;
    @Override public void run(String... args) {
        esService.ensureIndex();   // 启动即建索引（幂等：已存在则跳过）
    }
}
```

> 注意：它只建**空索引**，不会把 MySQL 里的存量笔记灌进来。存量数据的回填见 6.1。

### 5.4 EsConsumer.java —— 消费 MQ 写 ES

**作用**：监听 `note.es.queue`（Day6 已声明，今天正式启用）。收到 `NoteEvent` 后：按 noteId 回查数据库拿最新笔记 → 调 `esService.indexNote()` 写入 ES。

```java
@RabbitListener(queues = RabbitConfig.NOTE_ES_QUEUE)
public void onNoteEvent(NoteEvent event) {
    Note note = noteMapper.selectById(event.getNoteId()); // 回查库取最新内容
    if (note == null) return;                             // 笔记已删，直接忽略
    esService.indexNote(note);                            // 写入 ES（按 ID 覆盖，幂等）
}
```

**关键点**：ES 不可用时这里选择 `throw e` 让消息**重新入队**而不是丢弃（生产环境应配置死信队列，呼应 Day6），保证“ES 恢复后消息不丢、最终能索引上”。

### 5.5 RateLimitInterceptor.java —— 固定窗口限流（本课核心）

**作用**：一个 Spring MVC 拦截器，在请求进入 Controller **之前**（`preHandle`）做限流。返回 `true` 放行；返回 `false` 并写入 429 响应体则拦下。它就是 3.4.1 固定窗口算法的落地。

**关键逻辑逐行拆解**：

```java
// 1) 按 URI 分类，决定阈值：/like→1000，/search→500，其他→2000
if (uri.contains("/like"))        { category = "like";    limit = LIKE_LIMIT; }
else if (uri.contains("/search")) { category = "search";  limit = SEARCH_LIMIT; }
else                              { category = "default"; limit = DEFAULT_LIMIT; }

// 2) 每秒一个窗口 Key：rate:limit:like:1720000000（用当前秒数做后缀）
long second = System.currentTimeMillis() / 1000;
String key = RedisKeys.rateLimit(category + ":" + second);

// 3) INCR 原子自增；第一次写入（count==1）才设 2 秒过期，避免 Key 永久残留
Long count = stringRedisTemplate.opsForValue().increment(key);
if (count != null && count == 1) stringRedisTemplate.expire(key, 2, TimeUnit.SECONDS);

// 4) 超阈值 → 直接写 429 + JSON，返回 false 拦下请求（不进 Controller）
if (count != null && count > limit) {
    response.setStatus(429);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write("{\"code\":429,\"msg\":\"请求太频繁，请稍后再试\"}");
    return false;
}
return true; // 未超限，放行
```

> 为什么用 `INCR` 而不是“先 GET 再 SET”：`INCR` 是 Redis 的**原子操作**，高并发下不会出现“两个请求同时读到旧值各自 +1”的竞态——这正是限流正确性的关键（呼应 Day4 的原子性思想）。

### 5.6 WebConfig.java —— 注册拦截器 + RestTemplate Bean

**作用**：两件事。① 把 `RateLimitInterceptor` 注册到 Spring MVC，拦截所有 `/api/**` 接口；② 提供 `RestTemplate` Bean，供 `EsService` 调 ES REST API 用。

```java
@Override public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**"); // 拦所有 API
}
@Bean public RestTemplate restTemplate() { return new RestTemplate(); }       // EsService 依赖
```

> 少了这个配置类，限流拦截器根本不会生效（写了也不拦），`EsService` 也会因注入不到 `RestTemplate` 而启动失败。

### 5.7 NoteService.java —— 搜索走 ES + 降级、发布发消息

**作用**：在 Day7 基础上改两个方法，其余（缓存/分布式锁/Feed/热榜）保持不变。

**① `search()`：优先 ES，异常回退 MySQL（降级）**

```java
public List<NoteVO> search(String keyword, int page, int size, Long viewerId) {
    if (!StringUtils.hasText(keyword)) return Collections.emptyList();
    List<NoteVO> list;
    try {
        List<Long> ids = esService.searchIds(keyword.trim(), page, size);   // 先走 ES
        list = ids.isEmpty() ? Collections.emptyList()
                             : noteMapper.selectByIds(ids);                 // 拿 ID 回查组装
    } catch (Exception e) {
        log.warn("ES 搜索失败，降级到 MySQL：{}", e.getMessage());
        list = noteMapper.selectSearch(keyword.trim(), (page - 1) * size, size); // 兜底：MySQL LIKE
    }
    fillStatus(list, viewerId); mergeCounts(list);
    return list;
}
```

要点：ES 正常时**先搜 ID、再用 ID 回查 MySQL 组装完整 VO**（ES 只存搜索字段，点赞数等实时数据仍从 MySQL/Redis 取）；ES 抛异常时 `catch` 里回退到原来的 `selectSearch`（LIKE），功能不中断，只是性能降级。

**② `publish()`：发布后发 MQ 异步建索引**

```java
noteMapper.insert(note);                    // 先落库（MySQL 是最终归宿）
feedService.pushNote(note.getId(), userId); // Day7 的 Feed 推送保留
rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE,
        RabbitConfig.NOTE_ES_ROUTING_KEY, new NoteEvent(note.getId())); // ★发 ES 索引消息
```

要点：发 MQ 是**发布主流程的最后一步且异步**——即使 ES 建索引慢或失败，也不影响“发布成功”这个核心动作（削峰解耦）。

### 5.8 NoteController.java —— 热榜降级兜底

**作用**：给 `hot` 接口套一层 try/catch。正常时返回 Redis 热榜；一旦 `noteService.hot()` 抛异常（如 Redis 挂了），不再返回 500，而是**降级返回“最新笔记”列表**，保证首页永远有内容。

```java
@GetMapping("/hot")
public Result<List<NoteVO>> hot(
        @RequestHeader(value = "X-User-Id", required = false) Long viewerId) {
    try {
        return Result.ok(noteService.hot(viewerId));           // 正常：Redis 热榜
    } catch (Exception e) {
        log.error("热门榜异常，降级返回最新列表：{}", e.getMessage());
        return Result.ok(noteService.latest(1, 10, viewerId));  // 兜底：最新笔记
    }
}
```

这就是 3.5 “宁可给一个够用的结果，也不给一个报错的页面”的最直接落地。其余接口（list/follow/search/detail/publish）与 Day7 一致。

## 六、验证与压测

### 6.1 ES 搜索验证

**先搞清楚数据从哪来**：`EsInitRunner` 启动时只建**空索引**，不会自动同步 MySQL 存量笔记。ES 里的文档只有两个来源：① 3.3.4 动手时手工 `_bulk` 灌的演示数据；② 之后每发布一篇笔记，经 MQ 异步写入。

1. **看索引 / 文档数**：启动后 `curl "http://localhost:9200/_cat/indices?v"` 能看到 `xhs_notes`；`curl "http://localhost:9200/xhs_notes/_count"` 看当前文档数（做过 3.3.4 则 ≥ 5）；
2. **发布 → 秒级可搜**：发布一篇标题含“环岛骑行”的新笔记，等 1~2 秒（NRT 刷新 + MQ 消费），搜索“环岛骑行”能命中它；
3. **搜索走 ES 不走 MySQL**：搜索“三亚”，结果与 MySQL 版一致，但后端控制台**无 LIKE SQL**（说明命中了 ES 分支）；
4. **存量回填（可选，让搜索覆盖全部老笔记）**：ES 默认不含 MySQL 已有的 36 篇存量笔记。要让它们也可搜，做一次性 `_bulk` 导入（方法同 3.3.4，把 `seed-test-data.sql` 里的笔记标题/正文/标签按 `_doc/{真实 noteId}` 批量灌入）。教学演示中，用 3.3.4 的演示数据 + 现场发布几篇，已足够验证搜索链路；
5. **模拟降级**：临时把 `EsService` 的 `ES_BASE` 地址改错，重启，搜索仍返回结果（`catch` 分支走 MySQL 兜底），且日志打印“ES 搜索失败，降级到 MySQL”。

### 6.2 限流验证（核心验收）

1. JMeter 压测点赞接口：并发 300、循环，使 QPS 明显超过 1000；
2. 观察聚合报告：出现约 `429` 状态码的响应，响应体为"请求太频繁"；
3. 同时压测正常阈值内的接口：不受影响。

### 6.3 降级验证

1. 临时执行 `docker stop xhs-redis`（或改错 Redis 配置）；
2. 访问首页热榜：**不报错**，展示的是"最新笔记"兜底内容；
3. 恢复 Redis，热榜恢复正常。

## 七、结果记录表

| 场景 | 优化前 | 优化后 | 是否达标 |
| --- | --- | --- | --- |
| 搜索"三亚"平均RT | | | |
| 搜索时MySQL压力 | 全表扫描 | 基本为0 | |
| 超限请求处理 | 继续压垮系统 | 429拦截 | |
| Redis故障时热榜 | 报错 | 兜底最新笔记 | |

## 八、课堂实战：把保护伞撑到更多接口（自己动手）

> 今天老师带做的限流和降级只盖了两个点，现在轮到你把同一套思路扩展到更多接口。

**任务一（必做）：评论接口限流**

恶意刷评论是社区常见攻击。请在 `RateLimitInterceptor` 中新增一条规则：

- 命中条件：URI 包含 `/comments`；
- 阈值：每秒 100 次（比点赞更严，因为评论成本更高）；
- 超限返回 429。

实现只需在拦截器的阈值判断处加一个分支（参照 `/like`、`/search` 的写法）。
验收：JMeter 压评论接口超过 100 QPS，能看到 `{"code":429,"msg":"请求太频繁，请稍后再试"}`。

**任务二（挑战）：关注 Feed 降级**

关注页依赖 Redis 的 `feed:{userId}`，Redis 抖动时会报错。请给 `NoteService.followFeed` 加降级：

```java
try {
    ids = feedService.feedIds(viewerId, page, size);
} catch (Exception e) {
    log.warn("Feed 服务异常，降级走 MySQL", e);
    return noteMapper.selectFollowFeed(viewerId, offset, size);  // 方法名以实际为准
}
```

验收：`docker stop xhs-redis` 后，关注页仍能返回列表（来自 MySQL），不报 500。
（这个降级会连坐你 Day7 的分享/关注功能 —— 它们也依赖 Redis，观察一下哪些功能还能用、哪些挂了，想想为什么。）

**验收标准**：

- [ ] 评论接口超 100 QPS 后出现 429，未超限请求不受影响；
- [ ] 停止 Redis 后关注页返回数据不报错；
- [ ] 恢复 Redis 后所有功能自动恢复正常（无需重启）。

**选做挑战**：现在评论限流是全局窗口，一个大V的粉丝集体评论会互相影响。怎么改成“每个用户每秒最多 5 条评论”？（提示：Key 里拼上 userId）

## 九、思考题

1. ES 的数据来自 MQ 异步同步，用户刚发布就搜索可能搜不到，业务上能接受吗？怎么向用户解释？
2. 固定窗口限流有什么缺陷？（提示：窗口边界的2倍流量）滑动窗口/令牌桶怎么改进？
3. 除了"返回最新笔记"，降级还能有哪些形式？（提示：静态页、默认值、排队提示）
