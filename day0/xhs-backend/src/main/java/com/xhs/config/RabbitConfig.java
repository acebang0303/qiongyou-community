package com.xhs.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Day6：RabbitMQ 拓扑
 *
 * xhs.exchange (topic)
 *   like.db.#        → like.db.queue         点赞异步落库
 *   comment.notify.# → comment.notify.queue  评论通知
 *   note.es.#        → note.es.queue         笔记同步ES（Day8 消费）
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "xhs.exchange";

    public static final String LIKE_DB_QUEUE = "like.db.queue";
    public static final String COMMENT_NOTIFY_QUEUE = "comment.notify.queue";
    public static final String NOTE_ES_QUEUE = "note.es.queue";

    public static final String LIKE_ROUTING_KEY = "like.db";
    public static final String COMMENT_ROUTING_KEY = "comment.notify";
    public static final String NOTE_ES_ROUTING_KEY = "note.es";

    /** JSON 消息转换器：消息体直接以 JSON 传输，方便调试 */
    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public TopicExchange xhsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue likeDbQueue() {
        return new Queue(LIKE_DB_QUEUE, true);
    }

    @Bean
    public Queue commentNotifyQueue() {
        return new Queue(COMMENT_NOTIFY_QUEUE, true);
    }

    @Bean
    public Queue noteEsQueue() {
        return new Queue(NOTE_ES_QUEUE, true);
    }

    @Bean
    public Binding likeDbBinding() {
        return BindingBuilder.bind(likeDbQueue()).to(xhsExchange()).with("like.db.#");
    }

    @Bean
    public Binding commentNotifyBinding() {
        return BindingBuilder.bind(commentNotifyQueue()).to(xhsExchange()).with("comment.notify.#");
    }

    @Bean
    public Binding noteEsBinding() {
        return BindingBuilder.bind(noteEsQueue()).to(xhsExchange()).with("note.es.#");
    }
}
