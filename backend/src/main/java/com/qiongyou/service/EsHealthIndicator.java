package com.qiongyou.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * ★ P2-1：ES 健康指标
 *
 * ES 是搜索的核心依赖，但项目用 RestTemplate 直连、Spring Boot 没有内置它的 health 指标，
 * 于是 /actuator/health 里看不到 ES。这里补一个：能查通 → UP，否则 DOWN 并带错误信息。
 * （ES 挂了搜索会降级到 MySQL，但可用性应当能从健康检查上看出来。）
 */
@Component
public class EsHealthIndicator implements HealthIndicator {

    @Autowired
    private EsService esService;

    @Override
    public Health health() {
        try {
            long count = esService.count();
            return Health.up().withDetail("documents", count).build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
