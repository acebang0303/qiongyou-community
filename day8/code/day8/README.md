# Day8 代码变更清单：ES 搜索 + 限流与降级

基于 Day7 的代码继续。

| 文件 | 操作 | 目标位置 |
| --- | --- | --- |
| `dto/NoteEvent.java` | 【新增】 | `com.xhs.dto` |
| `service/EsService.java` | 【新增】 | `com.xhs.service`（RestTemplate 调 ES REST API） |
| `service/EsInitRunner.java` | 【新增】 | `com.xhs.service`（启动时创建索引） |
| `consumer/EsConsumer.java` | 【新增】 | `com.xhs.consumer`（消费 note.es.queue 建索引） |
| `ratelimit/RateLimitInterceptor.java` | 【新增】 | `com.xhs.ratelimit`（Redis 固定窗口限流） |
| `config/WebConfig.java` | 【新增】 | `com.xhs.config`（注册拦截器 + RestTemplate Bean） |
| `service/NoteService.java` | 【替换】 | `com.xhs.service`（搜索走ES/发布发MQ） |
| `controller/NoteController.java` | 【替换】 | `com.xhs.controller`（热榜降级兜底） |

说明：

- ES 不引入官方客户端，直接用 `RestTemplate` 调 `http://localhost:9200` 的 REST API，
  学生可用 curl / Kibana 对照验证；
- 限流阈值为全局窗口阈值：点赞类 1000/秒、搜索 500/秒、其他 2000/秒，
  超限返回 429 `{"code":429,"msg":"请求太频繁，请稍后再试"}`；
- 降级策略：
  - 搜索：ES 异常 → 回退 MySQL LIKE；
  - 热榜：接口异常 → 返回最新笔记列表；
- Day6 已声明的 `note.es.queue` / `note.es` 路由键在当天正式启用。
