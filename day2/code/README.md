# Day2 代码变更清单：Redis 缓存（高并发读）

前提：`docker compose up -d` 已启动（Redis 容器 6379 就绪）。

| 文件 | 操作 | 目标位置 |
| --- | --- | --- |
| `pom.xml` | 【替换】 | `../../day1/xhs-backend/pom.xml` |
| `application.yml` | 【替换】 | `../../day1/xhs-backend/src/main/resources/application.yml` |
| `config/RedisConfig.java` | 【新增】 | `com.xhs.config` |
| `common/RedisKeys.java` | 【新增】 | `com.xhs.common` |
| `service/NoteService.java` | 【替换】 | `com.xhs.service` |

变更后重启后端，按《Day2 实训手册》验证。
