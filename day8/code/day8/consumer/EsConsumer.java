package com.xhs.consumer;

import com.xhs.config.RabbitConfig;
import com.xhs.dto.NoteEvent;
import com.xhs.entity.Note;
import com.xhs.mapper.NoteMapper;
import com.xhs.service.EsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Day8：ES 索引消费者
 * 消费 note.es.queue：收到笔记事件 → 查库取最新内容 → 写入 ES
 * ES 本身按文档ID覆盖写（PUT _doc/{id}），天然幂等
 */
@Slf4j
@Component
public class EsConsumer {

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private EsService esService;

    @RabbitListener(queues = RabbitConfig.NOTE_ES_QUEUE)
    public void onNoteEvent(NoteEvent event) {
        Note note = noteMapper.selectById(event.getNoteId());
        if (note == null) {
            return;
        }
        try {
            esService.indexNote(note);
            log.info("笔记 {} 已同步到 ES", note.getId());
        } catch (Exception e) {
            // ES 不可用时不阻塞队列，消息会被重新入队（生产环境应进入死信队列）
            log.error("同步 ES 失败：{}", e.getMessage());
            throw e;
        }
    }
}
