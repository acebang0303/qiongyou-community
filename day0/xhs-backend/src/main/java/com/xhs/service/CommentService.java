package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xhs.common.Result;
import com.xhs.entity.Comment;
import com.xhs.entity.Note;
import com.xhs.mapper.CommentMapper;
import com.xhs.mapper.NoteMapper;
import com.xhs.vo.CommentVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 评论服务（基线版：同步写 MySQL）
 * Day6 实训会把"评论 -> 通知"改造为 RabbitMQ 异步
 */
@Service
public class CommentService {

    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private NoteMapper noteMapper;

    /** 评论列表 */
    public List<CommentVO> listByNote(Long noteId) {
        return commentMapper.selectByNote(noteId);
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
        return Result.ok();
    }
}
