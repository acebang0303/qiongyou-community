package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xhs.AbstractIntegrationTest;
import com.xhs.common.RedisKeys;
import com.xhs.entity.NoteLike;
import com.xhs.mapper.NoteLikeMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ P2-2：对账任务（P1-7）
 * 以 MySQL 为准回填 Redis：清空 Redis 后跑对账，集合与计数都应与库里一致，且重复跑幂等。
 */
class ReconcileTaskTest extends AbstractIntegrationTest {

    @Autowired
    private ReconcileTask reconcileTask;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private NoteLikeMapper noteLikeMapper;

    private long dbLikeCount(long noteId) {
        Long n = noteLikeMapper.selectCount(
                new LambdaQueryWrapper<NoteLike>().eq(NoteLike::getNoteId, noteId));
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("Redis 被清空后，对账把 MySQL 的点赞关系与计数回填回去")
    void backfillsRedisFromMysql() {
        long noteId = 1L;
        redis.delete(RedisKeys.like(noteId));
        redis.delete(RedisKeys.likeCount(noteId));

        reconcileTask.reconcile();

        long dbCount = dbLikeCount(noteId);
        assertThat(dbCount).isGreaterThan(0);
        assertThat(redis.opsForSet().size(RedisKeys.like(noteId))).isEqualTo(dbCount);
        assertThat(redis.opsForValue().get(RedisKeys.likeCount(noteId))).isEqualTo(String.valueOf(dbCount));
    }

    @Test
    @DisplayName("对账幂等：连跑两次结果不变")
    void reconcileIsIdempotent() {
        long noteId = 2L;
        redeem(noteId);

        reconcileTask.reconcile();
        Long first = redis.opsForSet().size(RedisKeys.like(noteId));
        reconcileTask.reconcile();

        assertThat(redis.opsForSet().size(RedisKeys.like(noteId))).isEqualTo(first);
        assertThat(first).isEqualTo(dbLikeCount(noteId));
    }

    private void redeem(long noteId) {
        redis.delete(RedisKeys.like(noteId));
        redis.delete(RedisKeys.likeCount(noteId));
    }
}
