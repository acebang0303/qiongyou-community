package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xhs.common.RedisKeys;
import com.xhs.common.Result;
import com.xhs.config.RabbitConfig;
import com.xhs.dto.LikeEvent;
import com.xhs.entity.Follow;
import com.xhs.mapper.FollowMapper;
import com.xhs.mapper.NoteMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * Day7 版本（在 Day6 基础上）：
 * 互动成功后，实时累加热度到 hot:notes 榜单 ZSet
 *   点赞 +1 / 取消点赞 -1 / 收藏 +2 / 取消收藏 -2
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

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private HotService hotService;

    /** 幂等点赞 + 发送落库消息 + 累加热度 */
    public Result<Void> like(Long noteId, Long userId) {
        if (noteMapper.selectById(noteId) == null) {
            return Result.fail(404, "笔记不存在");
        }
        Long result = redisTemplate.execute(LIKE_SCRIPT,
                Arrays.asList(RedisKeys.like(noteId), RedisKeys.likeCount(noteId)),
                userId.toString());
        if (result == null || result == 0) {
            return Result.fail("您已经点过赞了");
        }
        sendLikeEvent(noteId, userId, true);
        // ★ Day7：点赞 → 热度 +1
        hotService.addHeat(noteId, HotService.WEIGHT_LIKE);
        return Result.ok();
    }

    /** 幂等取消点赞 + 发送落库消息 + 回退热度 */
    public Result<Void> unlike(Long noteId, Long userId) {
        Long result = redisTemplate.execute(UNLIKE_SCRIPT,
                Arrays.asList(RedisKeys.like(noteId), RedisKeys.likeCount(noteId)),
                userId.toString());
        if (result != null && result == 1) {
            sendLikeEvent(noteId, userId, false);
            // ★ Day7：取消点赞 → 热度 -1
            hotService.addHeat(noteId, -HotService.WEIGHT_LIKE);
        }
        return Result.ok();
    }

    /** 幂等收藏 + 累加热度 */
    public Result<Void> favorite(Long noteId, Long userId) {
        if (noteMapper.selectById(noteId) == null) {
            return Result.fail(404, "笔记不存在");
        }
        Long result = redisTemplate.execute(FAVORITE_SCRIPT,
                Arrays.asList(RedisKeys.favorite(noteId), RedisKeys.favoriteCount(noteId)),
                userId.toString());
        if (result == null || result == 0) {
            return Result.fail("您已经收藏过了");
        }
        // ★ Day7：收藏 → 热度 +2
        hotService.addHeat(noteId, HotService.WEIGHT_FAVORITE);
        return Result.ok();
    }

    /** 幂等取消收藏 + 回退热度 */
    public Result<Void> unfavorite(Long noteId, Long userId) {
        Long result = redisTemplate.execute(UNFAVORITE_SCRIPT,
                Arrays.asList(RedisKeys.favorite(noteId), RedisKeys.favoriteCount(noteId)),
                userId.toString());
        if (result != null && result == 1) {
            // ★ Day7：取消收藏 → 热度 -2
            hotService.addHeat(noteId, -HotService.WEIGHT_FAVORITE);
        }
        return Result.ok();
    }

    /** 发送点赞事件 */
    private void sendLikeEvent(Long noteId, Long userId, boolean liked) {
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE,
                RabbitConfig.LIKE_ROUTING_KEY, new LikeEvent(noteId, userId, liked));
    }

    /** 关注用户（低频操作，保持 MySQL） */
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
        return Result.ok();
    }

    /** 取消关注 */
    public Result<Void> unfollow(Long userId, Long targetUserId) {
        followMapper.delete(
                new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getUserId, userId)
                        .eq(Follow::getFollowUserId, targetUserId));
        return Result.ok();
    }
}
