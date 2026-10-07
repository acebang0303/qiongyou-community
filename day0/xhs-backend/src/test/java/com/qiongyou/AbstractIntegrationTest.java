package com.qiongyou;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ★ P2-2 集成测试基类
 *
 * 用 Testcontainers 起临时 MySQL / Redis / RabbitMQ，隔离于开发环境、不碰开发数据。
 * - MySQL 用 `day0/sql/init.sql` 建库灌种子数据（由 maven-resources-plugin 拷进 test classpath）
 * - ES 不启容器：把 `xhs.es.base-url` 指到不可达端口，即完全隔离，
 *   同时顺带覆盖「ES 不可用 → 回退 MySQL」的降级路径
 * - 定时任务延迟调成 1 天，避免测试期间并发改动 Redis / 热榜导致断言不稳定
 *
 * ⚠️ 这里刻意用 **singleton 容器模式**（静态块启动、不标 @Testcontainers/@Container）：
 * 若用 @Container 声明在基类上，每个测试类会重启一次容器、映射端口随之变化，
 * 而 Spring 上下文是跨类缓存的、仍指向旧端口 → 后续测试类 Redis 连接超时。
 */
@SpringBootTest(properties = {
        "qiongyou.es.base-url=http://localhost:1",
        "qiongyou.reconcile.initial-delay-ms=86400000",
        "qiongyou.reconcile.fixed-delay-ms=86400000",
        "qiongyou.hot.rank-initial-delay-ms=86400000",
        "qiongyou.hot.rank-fixed-delay-ms=86400000"
})
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("xhs")
            .withInitScript("init.sql");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.12-management"));

    static {
        MYSQL.start();
        REDIS.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.redis.host", REDIS::getHost);
        registry.add("spring.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }
}
