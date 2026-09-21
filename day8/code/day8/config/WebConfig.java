package com.xhs.config;

import com.xhs.ratelimit.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Day8：
 * - 注册限流拦截器（拦截所有 /api/** 接口）
 * - 提供 RestTemplate Bean（EsService 调用 ES REST API 用）
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
