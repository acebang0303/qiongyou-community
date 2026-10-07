package com.qiongyou.common;

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

    /** 评论列表缓存（仅缓存第 1 页）：comment:list:1:1 */
    public static String commentList(Long noteId, int page) {
        return "comment:list:" + noteId + ":" + page;
    }

    /** 笔记缓存重建锁（Day5）：note:lock:1 */
    public static String noteLock(Long noteId) {
        return "note:lock:" + noteId;
    }

    /** 用户主页缓存（Day5）：user:1 */
    public static String user(Long userId) {
        return "user:" + userId;
    }

    /** 用户主页缓存重建锁（Day5）：user:lock:1 */
    public static String userLock(Long userId) {
        return "user:lock:" + userId;
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

    /** 分享关系 Set：share:1 */
    public static String share(Long noteId) {
        return "share:" + noteId;
    }

    /** 分享计数：share:count:1 */
    public static String shareCount(Long noteId) {
        return "share:count:" + noteId;
    }

    /** 关注关系 Set（Day6）：follow:5 表示 5 号用户关注了哪些人 */
    public static String follow(Long userId) {
        return "follow:" + userId;
    }

    /** 粉丝数计数（Day6）：fans:count:5 表示 5 号用户的粉丝数 */
    public static String fansCount(Long userId) {
        return "fans:count:" + userId;
    }

    /** 关注页收件箱 ZSet（Day7）：feed:5 */
    public static String feed(Long userId) {
        return "feed:" + userId;
    }

    /** 作者发件箱 ZSet（P1-9）：feed:outbox:5 —— 每个作者一条，大V 的粉丝读时来拉 */
    public static String feedOutbox(Long authorId) {
        return "feed:outbox:" + authorId;
    }

    /** 热点榜单 ZSet（Day7） */
    public static final String HOT_NOTES = "hot:notes";

    /** 热门话题榜 ZSet：hot:tags */
    public static String hotTags() {
        return "hot:tags";
    }

    /** 限流计数（Day8）：rate:limit:/api/notes/1/like */
    public static String rateLimit(String resource) {
        return "rate:limit:" + resource;
    }
}
