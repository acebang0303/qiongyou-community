package com.xhs.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * P0-4：事务后置执行工具
 *
 * 用于「事务提交后再做副作用」（如发送 MQ、写 Redis）：
 * 事务内直接发 MQ 且事务回滚时，消息无法撤回 → 产生幽灵消息；
 * 注册到 afterCommit 可保证只有 DB 写入真正落库后才发消息。
 *
 * 无活动事务时（如消费者未加事务、或单测直接调用）退化为立即执行。
 */
public final class TransactionHelper {

    private TransactionHelper() {
    }

    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
