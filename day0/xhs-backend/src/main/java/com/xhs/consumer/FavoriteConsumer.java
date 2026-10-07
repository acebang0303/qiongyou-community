package com.xhs.consumer;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xhs.config.RabbitConfig;
import com.xhs.dto.FavoriteEvent;
import com.xhs.entity.Note;
import com.xhs.entity.NoteFavorite;
import com.xhs.mapper.NoteFavoriteMapper;
import com.xhs.mapper.NoteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Day9：收藏落库消费者
 * 用户请求在 Redis 那一步就返回了，这里按处理能力匀速写 t_note_favorite 并维护 favorite_count
 * ★ P0-4：加事务，保证「插/删明细 + 改计数」原子
 */
@Slf4j
@Component
public class FavoriteConsumer {

    @Autowired
    private NoteFavoriteMapper favoriteMapper;
    @Autowired
    private NoteMapper noteMapper;

    @Transactional
    @RabbitListener(queues = RabbitConfig.FAVORITE_DB_QUEUE)
    public void onMessage(FavoriteEvent event) {
        if (event.isFavorited()) {
            doFavorite(event);
        } else {
            doUnfavorite(event);
        }
    }

    private void doFavorite(FavoriteEvent event) {
        try {
            favoriteMapper.insert(new NoteFavorite(event.getUserId(), event.getNoteId()));
            noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                    .eq(Note::getId, event.getNoteId())
                    .setSql("favorite_count = favorite_count + 1"));
        } catch (DuplicateKeyException e) {
            // 重复消费 / 并发重复：唯一索引 uk_user_note 兜底，直接忽略（消费端幂等）
            log.info("收藏记录已存在，跳过：noteId={}, userId={}", event.getNoteId(), event.getUserId());
        }
    }

    private void doUnfavorite(FavoriteEvent event) {
        int deleted = favoriteMapper.deleteByUserAndNote(event.getUserId(), event.getNoteId());
        if (deleted > 0) {
            noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                    .eq(Note::getId, event.getNoteId())
                    .setSql("favorite_count = GREATEST(favorite_count - 1, 0)"));
        }
    }
}
