package com.qiongyou.consumer;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiongyou.config.RabbitConfig;
import com.qiongyou.dto.ShareEvent;
import com.qiongyou.entity.Note;
import com.qiongyou.entity.NoteShare;
import com.qiongyou.mapper.NoteMapper;
import com.qiongyou.mapper.NoteShareMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分享落库消费者
 * 用户请求在 Redis 那一步就返回了，这里按处理能力匀速写 t_note_share 并维护 share_count
 * ★ P0-4：加事务，保证「插/删明细 + 改计数」原子
 */
@Slf4j
@Component
public class ShareConsumer {

    @Autowired
    private NoteShareMapper shareMapper;
    @Autowired
    private NoteMapper noteMapper;

    @Transactional
    @RabbitListener(queues = RabbitConfig.SHARE_DB_QUEUE)
    public void onMessage(ShareEvent event) {
        if (event.isShared()) {
            doShare(event);
        } else {
            doUnshare(event);
        }
    }

    private void doShare(ShareEvent event) {
        try {
            shareMapper.insert(new NoteShare(event.getNoteId(), event.getUserId()));
            noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                    .eq(Note::getId, event.getNoteId())
                    .setSql("share_count = share_count + 1"));
        } catch (DuplicateKeyException e) {
            // 重复消费 / 并发重复：唯一索引 uk_user_note 兜底，直接忽略（消费端幂等）
            log.info("分享记录已存在，跳过：noteId={}, userId={}", event.getNoteId(), event.getUserId());
        }
    }

    private void doUnshare(ShareEvent event) {
        int deleted = shareMapper.deleteByUserAndNote(event.getUserId(), event.getNoteId());
        if (deleted > 0) {
            noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                    .eq(Note::getId, event.getNoteId())
                    .setSql("share_count = GREATEST(share_count - 1, 0)"));
        }
    }
}
