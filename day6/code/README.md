# Day6 代码变更清单：RabbitMQ 异步与削峰填谷

基于 Day5 的代码继续。

| 文件 | 操作 | 目标位置 |
| --- | --- | --- |
| `pom.xml` | 【替换】 | `../../day1/xhs-backend/pom.xml`（新增 amqp starter） |
| `application.yml` | 【替换】 | `../../day1/xhs-backend/src/main/resources/application.yml`（新增 rabbitmq 配置） |
| `config/RabbitConfig.java` | 【新增】 | `com.xhs.config` |
| `dto/LikeEvent.java` | 【新增】 | `com.xhs.dto` |
| `dto/CommentEvent.java` | 【新增】 | `com.xhs.dto` |
| `service/InteractService.java` | 【替换】 | `com.xhs.service`（Day4基础上加发消息） |
| `service/CommentService.java` | 【替换】 | `com.xhs.service`（评论后发通知消息） |
| `consumer/LikeConsumer.java` | 【新增】 | `com.xhs.consumer` |
| `consumer/NotificationConsumer.java` | 【新增】 | `com.xhs.consumer` |

拓扑：

```text
xhs.exchange (topic)
  like.db.#        → like.db.queue         （LikeConsumer 落库）
  comment.notify.# → comment.notify.queue  （NotificationConsumer 通知）
  note.es.#        → note.es.queue         （Day8 的 EsConsumer 使用，今天先建好）
```
