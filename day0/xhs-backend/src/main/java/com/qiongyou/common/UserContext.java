package com.qiongyou.common;

/**
 * P0-2：当前登录用户上下文
 *
 * 由 AuthInterceptor 解析 JWT 后写入，Controller 从中取 userId，
 * 不再信任客户端传来的 X-User-Id 请求头。
 * 请求结束后由拦截器 afterCompletion 清理，避免线程池复用导致串号。
 */
public final class UserContext {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        HOLDER.set(userId);
    }

    /** 未登录返回 null */
    public static Long getUserId() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
