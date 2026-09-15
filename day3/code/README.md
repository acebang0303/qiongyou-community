# Day3 代码变更清单：Redis 高并发写（点赞/收藏）

基于 Day2 的代码继续。

| 文件 | 操作 | 目标位置 |
| --- | --- | --- |
| `service/InteractService.java` | 【替换】 | `com.xhs.service` |
| `service/NoteService.java` | 【替换】 | `com.xhs.service` |

变更要点：

1. 点赞/取消、收藏/取消全部改为 Redis（Set + INCR），不再写 MySQL；
2. 列表/详情的"是否点赞、点赞数"改为从 Redis 读取；
3. 关注关系（t_follow）低频，仍走 MySQL。
