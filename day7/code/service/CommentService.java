package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xhs.common.Result;
import com.xhs.config.RabbitConfig;
import com.xhs.dto.CommentEvent;
import com.xhs.entity.Comment;
import com.xhs.entity.Note;
import com.xhs.mapper.CommentMapper;
import com.xhs.mapper.NoteMapper;
import com.xhs.vo.CommentVO;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Day7 版本（在 Day6 基础上）：评论成功后累加热度（权重最高 = 5）
 */
@Service
public class CommentService {

    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private HotService hotService;

    /** 评论列表 */
    public List<CommentVO> listByNote(Long noteId) {
        return commentMapper.selectByNote(noteId);
    }

    /** 发表评论：插入评论 + 更新评论数 + 发送通知消息 + 累加热度 */
    public Result<Void> add(Long noteId, Long userId, String content) {
        if (!StringUtils.hasText(content)) {
            return Result.fail(400, "评论内容不能为空");
        }
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
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

        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE,
                RabbitConfig.COMMENT_ROUTING_KEY,
                new CommentEvent(noteId, note.getUserId(), userId, content.trim()));

        // ★ Day7：评论 → 热度 +5（权重最高的互动行为）
        hotService.addHeat(noteId, HotService.WEIGHT_COMMENT);
        return Result.ok();
    }
}
