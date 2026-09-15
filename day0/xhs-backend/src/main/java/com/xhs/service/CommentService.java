package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xhs.common.RedisKeys;
import com.xhs.common.Result;
import com.xhs.entity.Comment;
import com.xhs.entity.Note;
import com.xhs.mapper.CommentMapper;
import com.xhs.mapper.NoteMapper;
import com.xhs.vo.CommentVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 评论服务（基线版：同步写 MySQL）
 * Day6 实训会把"评论 -> 通知"改造为 RabbitMQ 异步
 */
@Service
public class CommentService {

    private static final long NOTE_CACHE_MINUTES = 5;

    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** 评论列表 */
    public List<CommentVO> listByNote(Long noteId) {
        String key = RedisKeys.commentList(noteId);
        List<CommentVO> list = (List<CommentVO>) redisTemplate.opsForValue().get(key);
        if (list == null) {
            list = commentMapper.selectByNote(noteId);
            if (list != null) {
                redisTemplate.opsForValue().set(key, list, NOTE_CACHE_MINUTES, TimeUnit.MINUTES);
            }
        }
        if (CollectionUtils.isEmpty(list)) {
            return Collections.emptyList();
        }

        return list;
    }



    /** 发表评论：插入评论 + 更新评论数 */
    public Result<Void> add(Long noteId, Long userId, String content) {
        if (!StringUtils.hasText(content)) {
            return Result.fail(400, "评论内容不能为空");
        }
        if (noteMapper.selectById(noteId) == null) {
            return Result.fail(404, "笔记不存在");
        }
        Comment comment = new Comment();
        comment.setNoteId(noteId);
        comment.setUserId(userId);
        comment.setContent(content.trim());
        commentMapper.insert(comment);

        noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                .eq(Note::getId, noteId)
                .setSql("comment_count = comment_count + 1"));
        redisTemplate.delete(RedisKeys.commentList(noteId));
        return Result.ok();
    }
}
