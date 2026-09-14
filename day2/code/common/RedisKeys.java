package com.xhs.common;

/**
 * Day2 引入：统一管理所有 Redis Key，避免硬编码散落各处
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 笔记详情缓存：note:1 */
    public static String note(Long noteId) {
        return "note:" + noteId;
    }

    /** 笔记缓存重建锁（Day5）：note:lock:1 */
    public static String noteLock(Long noteId) {
        return "note:lock:" + noteId;
    }

    /** 点赞关系 Set（Day3）：like:1 */
    public static String like(Long noteId) {
        return "like:" + noteId;
    }

    /** 点赞计数（Day3）：like:count:1 */
    public static String likeCount(Long noteId) {
        return "like:count:" + noteId;
    }

    /** 收藏关系 Set（Day3）：favorite:1 */
    public static String favorite(Long noteId) {
        return "favorite:" + noteId;
    }

    /** 收藏计数（Day3）：favorite:count:1 */
    public static String favoriteCount(Long noteId) {
        return "favorite:count:" + noteId;
    }

    /** 关注页收件箱 ZSet（Day7）：feed:5 */
    public static String feed(Long userId) {
        return "feed:" + userId;
    }

    /** 热点榜单 ZSet（Day7） */
    public static final String HOT_NOTES = "hot:notes";

    /** 限流计数（Day8）：rate:limit:/api/notes/1/like */
    public static String rateLimit(String resource) {
        return "rate:limit:" + resource;
    }
}
