# 琼游100天交流分享社区

一个前后端分离的内容社区系统（Spring Boot + Vue 3），覆盖**高并发场景下的读写治理**：
Redis 缓存与一致性、Lua 原子幂等、RabbitMQ 异步削峰、Feed 流与热榜、Elasticsearch 搜索、
限流与降级，并补齐了认证、事务、可观测性、集成测试与容器化部署。

> 各阶段的改动设计、踩坑点与验证记录见 [backend/CHANGELOG.md](backend/CHANGELOG.md)；
> 待办与完成状态见 [backend/PRODUCTION-TODO.md](backend/PRODUCTION-TODO.md)。

## 目录结构

```
.
├── backend/          Spring Boot 后端（核心，含设计文档与压测材料）
│   ├── src/          源码（com.qiongyou.*）
│   ├── docs/         压测数据 PERF.md、面试问答 INTERVIEW-QA.md、压测脚本 bench/
│   ├── es/           Elasticsearch 镜像（内置 IK 中文分词插件）
│   ├── Dockerfile    后端多阶段构建
│   ├── docker-compose.yml
│   ├── ci.sh         一键校验（编译 + Testcontainers 集成测试）
│   └── README.md     ★ 后端详细文档：架构 / 技术演进线 / 设计决策 / 快速开始
├── frontend/         Vue 3 + Vite + Element Plus 前端
├── sql/init.sql      建库建表 + 演示种子数据
└── benchmarks/       JMeter 压测脚本与结果（限流验证、优化前后对照）
```

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 2.7 / MyBatis-Plus / Java 8 |
| 存储 | MySQL 8（主数据）、Redis 7（缓存/计数/Feed/热榜）、Elasticsearch 8.8 + IK（搜索） |
| 消息 | RabbitMQ（topic exchange + 死信队列 + 发布确认） |
| 安全 | JWT 无状态认证 + BCrypt 密码 |
| 前端 | Vue 3 / Vite / Element Plus |
| 测试/部署 | JUnit 5 + Testcontainers、Docker Compose、Actuator |

## 快速开始

```bash
# 1) 只起中间件（MySQL/Redis/RabbitMQ/ES），后端在 IDE 里跑
cd backend && docker compose up -d

# 2) 连后端一起起（后端镜像 + 已内置 IK 的 ES 镜像）
cd backend && docker compose --profile app up -d
```

后端 API：<http://localhost:8080>　Swagger UI：<http://localhost:8080/swagger-ui/index.html>

演示账号：`xiaohong` / `123456`（更多见 [backend/README.md](backend/README.md)）。
**所有口令与密钥均为本地演示默认值，部署前必须用环境变量覆盖**，详见
[backend/README.md](backend/README.md) 的「安全说明」。

## 想看什么

| 想了解 | 去看 |
|---|---|
| 架构、技术演进线、关键设计决策 | [backend/README.md](backend/README.md) |
| 每个改动为什么这么做、踩过什么坑 | [backend/CHANGELOG.md](backend/CHANGELOG.md) |
| 优化前后压测对照与结论 | [backend/docs/PERF.md](backend/docs/PERF.md) |
| 高频面试追问与解答 | [backend/docs/INTERVIEW-QA.md](backend/docs/INTERVIEW-QA.md) |
| 还没做的事 | [backend/PRODUCTION-TODO.md](backend/PRODUCTION-TODO.md) |
