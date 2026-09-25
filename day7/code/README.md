# Day7 代码变更清单：Feed流与热点榜单（Redis ZSet）

基于 Day6 的代码继续。

| 文件 | 操作 | 目标位置 |
| --- | --- | --- |
| `mapper/FollowMapper.java` | 【替换】 | `com.xhs.mapper`（新增查询粉丝ID） |
| `mapper/NoteMapper.java` | 【替换】 | `com.xhs.mapper`（新增 selectByIds 批量查询） |
| `service/FeedService.java` | 【新增】 | `com.xhs.service` |
| `service/HotService.java` | 【新增】 | `com.xhs.service` |
| `service/InitHotRunner.java` | 【新增】 | `com.xhs.service`（启动时冷启动热榜） |
| `service/InteractService.java` | 【替换】 | `com.xhs.service`（点赞/收藏累加热度） |
| `service/CommentService.java` | 【替换】 | `com.xhs.service`（评论累加热度） |
| `service/NoteService.java` | 【替换】 | `com.xhs.service`（关注页/热榜/发布改造） |

说明：

- Feed 采用**推模式（写扩散）**：发布笔记时写入每个粉丝的 `feed:{userId}` ZSet；
- 热榜 `hot:notes` 用 ZINCRBY 实时累加：点赞+1、收藏+2、评论+5；
- ZSet 相关操作使用 `StringRedisTemplate`（纯字符串序列化，score/member 可读）。
