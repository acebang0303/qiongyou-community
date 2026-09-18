package com.xhs.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xhs.config.RabbitConfig;
import com.xhs.dto.LikeEvent;
import com.xhs.entity.Note;
import com.xhs.entity.NoteLike;
import com.xhs.mapper.NoteLikeMapper;
import com.xhs.mapper.NoteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Day6：点赞落库消费者
 * 按消费者的处理能力匀速写 MySQL —— 这就是"削峰填谷"
 */
@Slf4j
@Component
public class LikeConsumer {

    @Autowired
    private NoteLikeMapper likeMapper;
    @Autowired
    private NoteMapper noteMapper;

    @RabbitListener(queues = RabbitConfig.LIKE_DB_QUEUE)
    public void onLikeEvent(LikeEvent event) {
        if (event.isLiked()) {
            doLike(event);
        } else {
            doUnlike(event);
        }
    }

    private void doLike(LikeEvent event) {
        try {
            likeMapper.insert(new NoteLike(event.getUserId(), event.getNoteId()));
            noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                    .eq(Note::getId, event.getNoteId())
                    .setSql("like_count = like_count + 1"));
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：消息重复投递/并发时不会重复落库（消费端幂等）
            log.info("点赞记录已存在，跳过：noteId={}, userId={}", event.getNoteId(), event.getUserId());
        }
    }

    private void doUnlike(LikeEvent event) {
        int deleted = likeMapper.delete(
                new LambdaQueryWrapper<NoteLike>()
                        .eq(NoteLike::getUserId, event.getUserId())
                        .eq(NoteLike::getNoteId, event.getNoteId()));
        if (deleted > 0) {
            noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                    .eq(Note::getId, event.getNoteId())
                    .setSql("like_count = GREATEST(like_count - 1, 0)"));
        }
    }
}
