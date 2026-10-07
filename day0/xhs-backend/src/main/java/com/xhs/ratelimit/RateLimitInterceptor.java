package com.xhs.ratelimit;

import com.xhs.common.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

/**
 * Day8：Redis 固定窗口限流拦截器
 *
 * 原理：每秒一个计数 Key（INCR），超过阈值直接拒绝
 *   点赞类接口   /api/interact/like/**   → 1000 次/秒
 *   搜索接口     /api/notes/search       → 500 次/秒
 *   其他接口                              → 2000 次/秒
 *
 * 超限返回：429 {"code":429,"msg":"请求太频繁，请稍后再试"}
 *
 * 注意：固定窗口存在"窗口边界突刺"问题（临界两秒可达 2 倍阈值），
 * 这是课堂讨论点，进阶可改为滑动窗口 / 令牌桶（Redisson RRateLimiter）。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final long LIKE_LIMIT = 1000;
    private static final long SEARCH_LIMIT = 500;
    private static final long DEFAULT_LIMIT = 2000;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String uri = request.getRequestURI();

        String category;
        long limit;
        if (uri.contains("/like")) {
            category = "like";
            limit = LIKE_LIMIT;
        } else if (uri.contains("/search")) {
            category = "search";
            limit = SEARCH_LIMIT;
        } else {
            category = "default";
            limit = DEFAULT_LIMIT;
        }

        // 每秒一个窗口：rate:limit:like:1720000000
        long second = System.currentTimeMillis() / 1000;
        String key = RedisKeys.rateLimit(category + ":" + second);

        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            // 第一次写入时设置过期时间，避免 Key 残留
            stringRedisTemplate.expire(key, 2, TimeUnit.SECONDS);
        }
        if (count != null && count > limit) {
            log.warn("限流触发：{} 当前窗口计数 {}", uri, count);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":429,\"msg\":\"请求太频繁，请稍后再试\"}");
            return false;
        }
        return true;
    }
}
