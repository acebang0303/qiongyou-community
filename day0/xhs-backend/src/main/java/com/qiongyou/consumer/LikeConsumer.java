package com.qiongyou.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiongyou.config.RabbitConfig;
import com.qiongyou.dto.LikeEvent;
import com.qiongyou.entity.Note;
import com.qiongyou.entity.NoteLike;
import com.qiongyou.mapper.NoteLikeMapper;
import com.qiongyou.mapper.NoteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Day6：点赞落库消费者
 * 按消费者的处理能力匀速写 MySQL —— 这就是"削峰填谷"
 * ★ P0-4：加事务，保证「插/删明细 + 改计数」原子；抛异常则消息重新入队
 */
@Slf4j
@Component
public class LikeConsumer {

    @Autowired
    private NoteLikeMapper likeMapper;
    @Autowired
    private NoteMapper noteMapper;

    @Transactional
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
