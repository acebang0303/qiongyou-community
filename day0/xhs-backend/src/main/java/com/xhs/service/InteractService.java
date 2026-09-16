package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xhs.common.RedisKeys;
import com.xhs.common.Result;
import com.xhs.entity.Follow;
import com.xhs.mapper.FollowMapper;
import com.xhs.mapper.NoteMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Day3 版本：点赞 / 收藏改为 Redis 承接
 *
 * 点赞关系：Set  like:{noteId}      member = userId
 * 点赞数量：String like:count:{noteId}
 * 数据库落库交给 Day6 的 MQ，这里不再直接写 MySQL
 *
 * 注意：本版本"判断 + 计数"是两条命令，存在原子性问题，Day4 用 Lua 解决
 */
@Service
public class InteractService {

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** 点赞：SADD + INCR */
    public Result<Void> like(Long noteId, Long userId) {
        String likeKey = RedisKeys.like(noteId);
        Long added = redisTemplate.opsForSet().add(likeKey, userId);
        if (noteMapper.selectById(noteId) == null) {
            return Result.fail(404, "笔记不存在");
        }
        Long added = redisTemplate.opsForSet()
                .add(RedisKeys.like(noteId), userId.toString());
        if (!Boolean.TRUE.equals(added)) {
            return Result.fail("您已经点过赞了");
        }
        redisTemplate.opsForValue().increment(RedisKeys.likeCount(noteId));
        return Result.ok();
    }

    /** 取消点赞：SREM + DECR（下限0） */
    public Result<Void> unlike(Long noteId, Long userId) {
        Long removed = redisTemplate.opsForSet()
                .remove(RedisKeys.like(noteId), userId.toString());
        if (removed != null && removed > 0) {
            Long count = redisTemplate.opsForValue().decrement(RedisKeys.likeCount(noteId));
            if (count != null && count < 0) {
                redisTemplate.opsForValue().set(RedisKeys.likeCount(noteId), 0);
            }
        }
        return Result.ok();
    }

    /** 收藏：与点赞同构 */
    public Result<Void> favorite(Long noteId, Long userId) {
        if (noteMapper.selectById(noteId) == null) {
            return Result.fail(404, "笔记不存在");
        }
        Long added = redisTemplate.opsForSet()
                .add(RedisKeys.favorite(noteId), userId.toString());
        if (!Boolean.TRUE.equals(added)) {
            return Result.fail("您已经收藏过了");
        }
        redisTemplate.opsForValue().increment(RedisKeys.favoriteCount(noteId));
        return Result.ok();
    }

    /** 取消收藏 */
    public Result<Void> unfavorite(Long noteId, Long userId) {
        Long removed = redisTemplate.opsForSet()
                .remove(RedisKeys.favorite(noteId), userId.toString());
        if (removed != null && removed > 0) {
            Long count = redisTemplate.opsForValue().decrement(RedisKeys.favoriteCount(noteId));
            if (count != null && count < 0) {
                redisTemplate.opsForValue().set(RedisKeys.favoriteCount(noteId), 0);
            }
        }
        return Result.ok();
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
