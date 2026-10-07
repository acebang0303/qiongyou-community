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
 * Day6 版本：评论入库后发送 CommentEvent，由通知消费者异步处理
 */
@Service
public class CommentService {

    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 评论列表 */
    public List<CommentVO> listByNote(Long noteId) {
        return commentMapper.selectByNote(noteId);
    }

    /** 发表评论：插入评论 + 更新评论数 + 发送通知消息 */
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

        // ★ Day6：通知动作异步化（不阻塞评论主流程）
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE,
                RabbitConfig.COMMENT_ROUTING_KEY,
                new CommentEvent(noteId, note.getUserId(), userId, content.trim()));
        return Result.ok();
    }
}
