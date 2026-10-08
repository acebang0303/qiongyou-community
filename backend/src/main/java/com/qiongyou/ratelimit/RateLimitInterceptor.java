package com.qiongyou.ratelimit;

import com.qiongyou.common.RedisKeys;
import com.qiongyou.common.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

/**
 * Day8：Redis 固定窗口限流拦截器
 * ★ P1-13：改为**按用户维度**限流（匿名按 IP），阈值全部 yml 可配
 *
 * 原理：每秒一个计数 Key（INCR），超过阈值直接拒绝
 *   Key：rate:limit:{u:用户ID | ip:来源IP}:{分类}:{秒}
 *   点赞类接口   /api/notes/{id}/like       → 20 次/秒/用户
 *   评论接口     /api/notes/{id}/comments   → 5  次/秒/用户（Day8 选做挑战指定）
 *   搜索接口     /api/notes/search          → 10 次/秒/用户
 *   其他接口                                → 50 次/秒/用户
 *
 * 超限返回：429 {"code":429,"msg":"请求太频繁，请稍后再试"}
 * Redis 故障时：fail-open（放行不限流），避免限流组件拖垮全部接口
 *
 * 注意：本拦截器注册在 AuthInterceptor **之后**，才能从 UserContext 读到 userId。
 * 固定窗口存在"窗口边界突刺"（临界两秒可达 2 倍阈值），进阶可改滑动窗口/令牌桶。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${qiongyou.rate-limit.like:20}")
    private long likeLimit;
    @Value("${qiongyou.rate-limit.comment:5}")
    private long commentLimit;
    @Value("${qiongyou.rate-limit.search:10}")
    private long searchLimit;
    @Value("${qiongyou.rate-limit.default:50}")
    private long defaultLimit;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String uri = request.getRequestURI();

        String category;
        long limit;
        if (uri.contains("/like")) {
            category = "like";
            limit = likeLimit;
        } else if (uri.contains("/comments")) {
            category = "comment";
            limit = commentLimit;
        } else if (uri.contains("/search")) {
            category = "search";
            limit = searchLimit;
        } else {
            category = "default";
            limit = defaultLimit;
        }

        // 限流维度：登录用户按 userId，匿名按来源 IP（否则所有匿名请求挤在同一个桶里）
        Long userId = UserContext.getUserId();
        String principal = userId != null ? ("u" + userId) : ("ip" + clientIp(request));
        long second = System.currentTimeMillis() / 1000;
        String key = RedisKeys.rateLimit(principal + ":" + category + ":" + second);

        Long count;
        try {
            count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                // 第一次写入时设置过期时间，避免 Key 残留
                stringRedisTemplate.expire(key, 2, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // Redis 故障时 fail-open：放行不限流，避免限流组件拖垮全部接口
            log.warn("限流计数失败，本次放行：{}", e.getMessage());
            return true;
        }
        if (count != null && count > limit) {
            log.warn("限流触发：{} 主体 {} 当前窗口计数 {}", uri, principal, count);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":429,\"msg\":\"请求太频繁，请稍后再试\"}");
            return false;
        }
        return true;
    }

    /** 取客户端 IP：优先 X-Forwarded-For 的第一段（经过网关/代理时） */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return request.getRemoteAddr();
    }
}
