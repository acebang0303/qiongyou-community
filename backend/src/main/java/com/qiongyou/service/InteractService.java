package com.qiongyou.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiongyou.common.RedisKeys;
import com.qiongyou.common.Result;
import com.qiongyou.config.RabbitConfig;
import com.qiongyou.dto.FavoriteEvent;
import com.qiongyou.dto.LikeEvent;
import com.qiongyou.dto.ShareEvent;
import com.qiongyou.entity.Follow;
import com.qiongyou.mapper.FollowMapper;
import com.qiongyou.mapper.NoteMapper;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * 
 * Redis 原子操作成功 → 发送 LikeEvent 到 MQ → 消费者异步落库
 * 用户请求在 Redis 这一步就返回了，不再等待数据库
 */
@Service
public class InteractService {

    private static final DefaultRedisScript<Long> LIKE_SCRIPT = new DefaultRedisScript<>(
            "local added = redis.call('SADD', KEYS[1], ARGV[1]) " +
            "if added == 1 then redis.call('INCR', KEYS[2]) return 1 else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> UNLIKE_SCRIPT = new DefaultRedisScript<>(
            "local removed = redis.call('SREM', KEYS[1], ARGV[1]) " +
            "if removed == 1 then " +
            "  local c = redis.call('DECR', KEYS[2]) " +
            "  if c < 0 then redis.call('SET', KEYS[2], 0) end " +
            "  return 1 " +
            "else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> FAVORITE_SCRIPT = new DefaultRedisScript<>(
            "local added = redis.call('SADD', KEYS[1], ARGV[1]) " +
            "if added == 1 then redis.call('INCR', KEYS[2]) return 1 else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> UNFAVORITE_SCRIPT = new DefaultRedisScript<>(
            "local removed = redis.call('SREM', KEYS[1], ARGV[1]) " +
            "if removed == 1 then " +
            "  local c = redis.call('DECR', KEYS[2]) " +
            "  if c < 0 then redis.call('SET', KEYS[2], 0) end " +
            "  return 1 " +
            "else return 0 end",
            Long.class);

    /** 分享：SADD成功才INCR，返回1=成功，0=已经分享过 */
    private static final DefaultRedisScript<Long> SHARE_SCRIPT = new DefaultRedisScript<>(
            "local added = redis.call('SADD', KEYS[1], ARGV[1]) " +
            "if added == 1 then redis.call('INCR', KEYS[2]) return 1 else return 0 end",
            Long.class);

    /** 取消分享：SREM成功才DECR（下限0），返回1=成功，0=本来就没分享 */
    private static final DefaultRedisScript<Long> UNSHARE_SCRIPT = new DefaultRedisScript<>(
            "local removed = redis.call('SREM', KEYS[1], ARGV[1]) " +
            "if removed == 1 then " +
            "  local c = redis.call('DECR', KEYS[2]) " +
            "  if c < 0 then redis.call('SET', KEYS[2], 0) end " +
            "  return 1 " +
            "else return 0 end",
            Long.class);

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private FollowMapper followMapper;
    /** 执行 Lua 脚本专用：String 序列化，避免 ARGV 被写成带引号的 JSON 字符串 */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private HotService hotService;

    /** 幂等点赞 + 发送落库消息 */
    public Result<Void> like(Long noteId, Long userId) {
        if (noteMapper.selectById(noteId) == null) {
            return Result.fail(404, "笔记不存在");
        }
        Long result = stringRedisTemplate.execute(LIKE_SCRIPT,
                Arrays.asList(RedisKeys.like(noteId), RedisKeys.likeCount(noteId)),
                userId.toString());
        if (result == null || result == 0) {
            return Result.fail("您已经点过赞了");
        }
        // ★ 真正落库的动作交给 MQ（削峰）
        sendLikeEvent(noteId, userId, true);
        // ★ 点赞 → 热度 +1
        hotService.addHeat(noteId, HotService.WEIGHT_LIKE);
        return Result.ok();
    }

    /** 幂等取消点赞 + 发送落库消息 */
    public Result<Void> unlike(Long noteId, Long userId) {
        Long result = stringRedisTemplate.execute(UNLIKE_SCRIPT,
                Arrays.asList(RedisKeys.like(noteId), RedisKeys.likeCount(noteId)),
                userId.toString());
        if (result != null && result == 1) {
            sendLikeEvent(noteId, userId, false);
            // ★ 取消点赞 → 热度 -1
            hotService.addHeat(noteId, -HotService.WEIGHT_LIKE);
        }
        return Result.ok();
    }

    /** 幂等收藏 + 发送落库消息 */
    public Result<Void> favorite(Long noteId, Long userId) {
        if (noteMapper.selectById(noteId) == null) {
            return Result.fail(404, "笔记不存在");
        }
        Long result = stringRedisTemplate.execute(FAVORITE_SCRIPT,
                Arrays.asList(RedisKeys.favorite(noteId), RedisKeys.favoriteCount(noteId)),
                userId.toString());
        if (result == null || result == 0) {
            return Result.fail("您已经收藏过了");
        }
        // ★ 落库（t_note_favorite + favorite_count）交给 MQ，消费者异步处理（削峰）
        sendFavoriteEvent(noteId, userId, true);
        // ★ 收藏 → 热度 +2
        hotService.addHeat(noteId, HotService.WEIGHT_FAVORITE);
        return Result.ok();
    }

    /** 幂等取消收藏 + 回退热度 + 发送落库消息 */
    public Result<Void> unfavorite(Long noteId, Long userId) {
        Long result = stringRedisTemplate.execute(UNFAVORITE_SCRIPT,
                Arrays.asList(RedisKeys.favorite(noteId), RedisKeys.favoriteCount(noteId)),
                userId.toString());
        if (result != null && result == 1) {
            sendFavoriteEvent(noteId, userId, false);
            // ★ 取消收藏 → 热度 -2
            hotService.addHeat(noteId, -HotService.WEIGHT_FAVORITE);
        }
        return Result.ok();
    }

    /** 幂等分享：Lua 脚本原子完成"去重 + 计数"，成功才发落库消息 */
    public Result<Void> share(Long noteId, Long userId) {
        Long result = stringRedisTemplate.execute(SHARE_SCRIPT,
                Arrays.asList(RedisKeys.share(noteId), RedisKeys.shareCount(noteId)),
                userId.toString());
        if (result == null || result == 0) {
            return Result.fail("您已经分享过了");
        }
        // ★ 落库（t_note_share + share_count）交给 MQ，消费者异步处理（削峰）
        sendShareEvent(noteId, userId, true);
        return Result.ok();
    }

    /** 幂等取消分享：Lua 脚本原子完成"删除 + 计数（不减成负数）"，成功才发落库消息 */
    public Result<Void> unshare(Long noteId, Long userId) {
        Long result = stringRedisTemplate.execute(UNSHARE_SCRIPT,
                Arrays.asList(RedisKeys.share(noteId), RedisKeys.shareCount(noteId)),
                userId.toString());
        if (result != null && result == 1) {
            sendShareEvent(noteId, userId, false);
        }
        return Result.ok();
    }

    /** 发送点赞事件（★ P1-5：带 correlationId，便于 confirm 失败时定位） */
    private void sendLikeEvent(Long noteId, Long userId, boolean liked) {
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE,
                RabbitConfig.LIKE_ROUTING_KEY, new LikeEvent(noteId, userId, liked),
                new CorrelationData("like:" + noteId + ":" + userId));
    }

    /** 发送收藏事件 */
    private void sendFavoriteEvent(Long noteId, Long userId, boolean favorited) {
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE,
                RabbitConfig.FAVORITE_ROUTING_KEY, new FavoriteEvent(noteId, userId, favorited),
                new CorrelationData("favorite:" + noteId + ":" + userId));
    }

    /** 发送分享事件 */
    private void sendShareEvent(Long noteId, Long userId, boolean shared) {
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE,
                RabbitConfig.SHARE_ROUTING_KEY, new ShareEvent(noteId, userId, shared),
                new CorrelationData("share:" + noteId + ":" + userId));
    }

    /** 关注用户（低频操作，保持 MySQL；★ P1-3：成功后清双方主页缓存） */
    public Result<Void> follow(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            return Result.fail(400, "不能关注自己");
        }
        Long count = followMapper.selectCount(
                new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getUserId, userId)
                        .eq(Follow::getFollowUserId, targetUserId));
        if (count > 0) {
            return Result.fail("已经关注过了");
        }
        try {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(targetUserId);
            followMapper.insert(follow);
        } catch (DuplicateKeyException e) {
            return Result.fail("请勿重复关注");
        }
        // ★ P1-3：关注双向影响「我 followCount」与「对方 fansCount」，两个主页缓存都要失效
        evictUserCache(userId, targetUserId);
        return Result.ok();
    }

    /** 取消关注（★ P1-3：成功后清双方主页缓存） */
    public Result<Void> unfollow(Long userId, Long targetUserId) {
        int deleted = followMapper.delete(
                new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getUserId, userId)
                        .eq(Follow::getFollowUserId, targetUserId));
        if (deleted > 0) {
            evictUserCache(userId, targetUserId);
        }
        return Result.ok();
    }

    /** 删除用户主页缓存，下次读取时按 DB 重建（key 由 StringSerializer 编码，两个 template 等价） */
    private void evictUserCache(Long... userIds) {
        for (Long id : userIds) {
            stringRedisTemplate.delete(RedisKeys.user(id));
        }
    }
}
