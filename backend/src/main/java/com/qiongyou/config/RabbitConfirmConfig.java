package com.qiongyou.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * P1-5：MQ 发布端可靠性回调
 *
 * - ConfirmCallback：消息是否到达交换机（ack=false 表示没到交换机，消息丢了）
 *   注意：ack=true 只代表到达交换机，不代表已入队；入队失败走下面的 ReturnsCallback
 * - ReturnsCallback：交换机路由不到任何队列时回调（需 publisher-returns + mandatory）
 *
 * 这两个回调只做"失败可见"；真正的补偿由 P1-7 对账任务兜底。
 */
@Slf4j
@Component
public class RabbitConfirmConfig {

    public RabbitConfirmConfig(RabbitTemplate rabbitTemplate) {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("【MQ confirm 失败】消息未到达交换机：correlationId={}, cause={}",
                        correlationData != null ? correlationData.getId() : "unknown", cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "【MQ return】消息无法路由到队列：exchange={}, routingKey={}, replyText={}, body={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText(),
                new String(returned.getMessage().getBody())));
    }
}
