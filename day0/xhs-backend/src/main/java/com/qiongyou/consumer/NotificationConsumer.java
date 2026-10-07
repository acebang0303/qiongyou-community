package com.qiongyou.consumer;

import com.qiongyou.config.RabbitConfig;
import com.qiongyou.dto.CommentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Day6：评论通知消费者
 * 真实系统中这里会写通知表/推站内信/推送，实训版打印日志模拟
 */
@Slf4j
@Component
public class NotificationConsumer {

    @RabbitListener(queues = RabbitConfig.COMMENT_NOTIFY_QUEUE)
    public void onCommentEvent(CommentEvent event) {
        // 自己评论自己不通知
        if (event.getAuthorId().equals(event.getUserId())) {
            return;
        }
        log.info("【站内通知】用户 {} 评论了你的笔记 {}：{}",
                event.getUserId(), event.getNoteId(), event.getContent());
    }
}
