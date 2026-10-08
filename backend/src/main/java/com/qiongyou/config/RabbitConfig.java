package com.qiongyou.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 拓扑
 *
 * xhs.exchange (topic)
 *   like.db.#        → like.db.queue         点赞异步落库
 *   favorite.db.#    → favorite.db.queue     收藏异步落库
 *   share.db.#       → share.db.queue        分享异步落库
 *   comment.notify.# → comment.notify.queue  评论通知
 *   note.es.#        → note.es.queue         笔记同步ES（异步消费）
 *
 * ★ P1-6：每个业务队列挂死信交换机 xhs.dlx（direct），消费重试耗尽后进对应 *.dlq
 */
@Slf4j
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "xhs.exchange";
    /** 死信交换机 */
    public static final String DLX = "xhs.dlx";

    public static final String LIKE_DB_QUEUE = "like.db.queue";
    public static final String FAVORITE_DB_QUEUE = "favorite.db.queue";
    public static final String SHARE_DB_QUEUE = "share.db.queue";
    public static final String COMMENT_NOTIFY_QUEUE = "comment.notify.queue";
    public static final String NOTE_ES_QUEUE = "note.es.queue";

    public static final String LIKE_DB_DLQ = "like.db.dlq";
    public static final String FAVORITE_DB_DLQ = "favorite.db.dlq";
    public static final String SHARE_DB_DLQ = "share.db.dlq";
    public static final String COMMENT_NOTIFY_DLQ = "comment.notify.dlq";
    public static final String NOTE_ES_DLQ = "note.es.dlq";

    public static final String LIKE_ROUTING_KEY = "like.db";
    public static final String FAVORITE_ROUTING_KEY = "favorite.db";
    public static final String SHARE_ROUTING_KEY = "share.db";
    public static final String COMMENT_ROUTING_KEY = "comment.notify";
    public static final String NOTE_ES_ROUTING_KEY = "note.es";

    /** JSON 消息转换器：消息体直接以 JSON 传输，方便调试 */
    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * ★ P1-6：消费重试耗尽后的兜底
     * 打印日志后抛出 AmqpRejectAndDontRequeueException → 消息被拒绝且不重回队列 → 投递到死信交换机
     */
    @Bean
    public MessageRecoverer messageRecoverer() {
        return (message, cause) -> {
            log.error("【MQ 消费失败→死信】body={}, cause={}",
                    new String(message.getBody()), cause.getMessage());
            throw new AmqpRejectAndDontRequeueException(cause);
        };
    }

    @Bean
    public TopicExchange xhsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX, true, false);
    }

    /** 业务队列统一带上「死信交换机 + 死信路由键(用队列名)」 */
    private static Map<String, Object> dlqArgs(String queueName) {
        Map<String, Object> args = new HashMap<>(2);
        args.put("x-dead-letter-exchange", DLX);
        args.put("x-dead-letter-routing-key", queueName);
        return args;
    }

    @Bean
    public Queue likeDbQueue() {
        return new Queue(LIKE_DB_QUEUE, true, false, false, dlqArgs(LIKE_DB_QUEUE));
    }

    @Bean
    public Queue favoriteDbQueue() {
        return new Queue(FAVORITE_DB_QUEUE, true, false, false, dlqArgs(FAVORITE_DB_QUEUE));
    }

    @Bean
    public Queue shareDbQueue() {
        return new Queue(SHARE_DB_QUEUE, true, false, false, dlqArgs(SHARE_DB_QUEUE));
    }

    @Bean
    public Queue commentNotifyQueue() {
        return new Queue(COMMENT_NOTIFY_QUEUE, true, false, false, dlqArgs(COMMENT_NOTIFY_QUEUE));
    }

    @Bean
    public Queue noteEsQueue() {
        return new Queue(NOTE_ES_QUEUE, true, false, false, dlqArgs(NOTE_ES_QUEUE));
    }

    @Bean
    public Queue likeDbDlq() {
        return new Queue(LIKE_DB_DLQ, true);
    }

    @Bean
    public Queue favoriteDbDlq() {
        return new Queue(FAVORITE_DB_DLQ, true);
    }

    @Bean
    public Queue shareDbDlq() {
        return new Queue(SHARE_DB_DLQ, true);
    }

    @Bean
    public Queue commentNotifyDlq() {
        return new Queue(COMMENT_NOTIFY_DLQ, true);
    }

    @Bean
    public Queue noteEsDlq() {
        return new Queue(NOTE_ES_DLQ, true);
    }

    @Bean
    public Binding likeDbBinding() {
        return BindingBuilder.bind(likeDbQueue()).to(xhsExchange()).with("like.db.#");
    }

    @Bean
    public Binding favoriteDbBinding() {
        return BindingBuilder.bind(favoriteDbQueue()).to(xhsExchange()).with("favorite.db.#");
    }

    @Bean
    public Binding shareDbBinding() {
        return BindingBuilder.bind(shareDbQueue()).to(xhsExchange()).with("share.db.#");
    }

    @Bean
    public Binding commentNotifyBinding() {
        return BindingBuilder.bind(commentNotifyQueue()).to(xhsExchange()).with("comment.notify.#");
    }

    @Bean
    public Binding noteEsBinding() {
        return BindingBuilder.bind(noteEsQueue()).to(xhsExchange()).with("note.es.#");
    }

    @Bean
    public Binding likeDbDlqBinding() {
        return BindingBuilder.bind(likeDbDlq()).to(dlxExchange()).with(LIKE_DB_QUEUE);
    }

    @Bean
    public Binding favoriteDbDlqBinding() {
        return BindingBuilder.bind(favoriteDbDlq()).to(dlxExchange()).with(FAVORITE_DB_QUEUE);
    }

    @Bean
    public Binding shareDbDlqBinding() {
        return BindingBuilder.bind(shareDbDlq()).to(dlxExchange()).with(SHARE_DB_QUEUE);
    }

    @Bean
    public Binding commentNotifyDlqBinding() {
        return BindingBuilder.bind(commentNotifyDlq()).to(dlxExchange()).with(COMMENT_NOTIFY_QUEUE);
    }

    @Bean
    public Binding noteEsDlqBinding() {
        return BindingBuilder.bind(noteEsDlq()).to(dlxExchange()).with(NOTE_ES_QUEUE);
    }
}
