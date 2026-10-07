package com.qiongyou.config;

import com.qiongyou.auth.AuthInterceptor;
import com.qiongyou.ratelimit.RateLimitInterceptor;
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
 * P0-2：新增认证拦截器，解析 JWT 写入 UserContext
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;
    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 顺序很重要：★ P1-13 限流要按用户维度，必须先认证把 userId 放进 UserContext，再限流
        // 认证拦截器：匿名放行、无效 token 直接 401；login 接口不带 token，天然放行
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
