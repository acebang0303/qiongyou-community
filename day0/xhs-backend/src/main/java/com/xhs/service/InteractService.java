package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xhs.common.Result;
import com.xhs.entity.Follow;
import com.xhs.entity.Note;
import com.xhs.entity.NoteFavorite;
import com.xhs.entity.NoteLike;
import com.xhs.mapper.FollowMapper;
import com.xhs.mapper.NoteFavoriteMapper;
import com.xhs.mapper.NoteLikeMapper;
import com.xhs.mapper.NoteMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 互动服务：点赞 / 收藏 / 关注（基线版：全部同步写 MySQL）
 *
 * 有意保留的"问题"，作为后续实训素材：
 * 1. 高并发写：所有请求直接落库           -> Day3 用 Redis 承接
 * 2. "先查后写"存在竞态条件，重复请求只靠唯一索引兜底 -> Day4 做幂等设计
 * 3. 瞬间流量无削峰                        -> Day6 用 RabbitMQ 异步
 */
@Service
public class InteractService {

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private NoteLikeMapper likeMapper;
    @Autowired
    private NoteFavoriteMapper favoriteMapper;
    @Autowired
    private FollowMapper followMapper;

    /**
     * 点赞（基线版实现：先查后插 + 更新计数，两条以上 SQL 同步落库）
     */
    public Result<Void> like(Long noteId, Long userId) {
        if (noteMapper.selectById(noteId) == null) {
            return Result.fail(404, "笔记不存在");
        }
        // 先查：是否已经点过赞（注意：并发下"先查后写"有竞态窗口）
        Long count = likeMapper.selectCount(
                new LambdaQueryWrapper<NoteLike>()
                        .eq(NoteLike::getUserId, userId)
                        .eq(NoteLike::getNoteId, noteId));
        if (count > 0) {
            return Result.fail("您已经点过赞了");
        }
        try {
            likeMapper.insert(new NoteLike(userId, noteId));
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：并发下重复插入会被拦截
            return Result.fail("请勿重复点赞");
        }
        noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                .eq(Note::getId, noteId)
                .setSql("like_count = like_count + 1"));
        return Result.ok();
    }

    /** 取消点赞 */
    public Result<Void> unlike(Long noteId, Long userId) {
        int deleted = likeMapper.delete(
                new LambdaQueryWrapper<NoteLike>()
                        .eq(NoteLike::getUserId, userId)
                        .eq(NoteLike::getNoteId, noteId));
        if (deleted > 0) {
            noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                    .eq(Note::getId, noteId)
                    .setSql("like_count = GREATEST(like_count - 1, 0)"));
        }
        return Result.ok();
    }

    /** 收藏（实现与点赞同构，同样存在问题） */
    public Result<Void> favorite(Long noteId, Long userId) {
        if (noteMapper.selectById(noteId) == null) {
            return Result.fail(404, "笔记不存在");
        }
        Long count = favoriteMapper.selectCount(
                new LambdaQueryWrapper<NoteFavorite>()
                        .eq(NoteFavorite::getUserId, userId)
                        .eq(NoteFavorite::getNoteId, noteId));
        if (count > 0) {
            return Result.fail("您已经收藏过了");
        }
        try {
            favoriteMapper.insert(new NoteFavorite(userId, noteId));
        } catch (DuplicateKeyException e) {
            return Result.fail("请勿重复收藏");
        }
        noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                .eq(Note::getId, noteId)
                .setSql("favorite_count = favorite_count + 1"));
        return Result.ok();
    }

    /** 取消收藏 */
    public Result<Void> unfavorite(Long noteId, Long userId) {
        int deleted = favoriteMapper.delete(
                new LambdaQueryWrapper<NoteFavorite>()
                        .eq(NoteFavorite::getUserId, userId)
                        .eq(NoteFavorite::getNoteId, noteId));
        if (deleted > 0) {
            noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                    .eq(Note::getId, noteId)
                    .setSql("favorite_count = GREATEST(favorite_count - 1, 0)"));
        }
        return Result.ok();
    }

    /** 关注用户 */
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
