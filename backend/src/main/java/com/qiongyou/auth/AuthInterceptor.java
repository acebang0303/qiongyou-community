package com.qiongyou.auth;

import com.qiongyou.common.JwtUtil;
import com.qiongyou.common.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * P0-2：认证拦截器
 *
 * 语义：Authorization: Bearer &lt;token&gt;
 *   - 未携带 header      → 放行（匿名），由各接口自行决定是否要求登录
 *   - 携带但无效/过期    → 直接 401，不再执行后续接口
 *   - 携带且有效         → 解析 userId 写入 UserContext
 *
 * 与限流拦截器并列注册在 WebConfig，仅作用于 /api/**。
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return true;
        }
        String token = header.substring(PREFIX.length()).trim();
        try {
            UserContext.set(jwtUtil.parseUserId(token));
        } catch (Exception e) {
            log.warn("token 无效或已过期：{}", e.getMessage());
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"登录已失效，请重新登录\"}");
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 关键：Tomcat 线程池复用，必须清理 ThreadLocal，否则下一个请求会读到上一个用户
        UserContext.clear();
    }
}
