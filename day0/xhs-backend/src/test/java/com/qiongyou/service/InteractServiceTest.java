package com.qiongyou.service;

import com.qiongyou.AbstractIntegrationTest;
import com.qiongyou.common.RedisKeys;
import com.qiongyou.common.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ P2-2：点赞的 Lua 原子幂等（Day4 的核心考点）
 *
 * 验证「判断 + 计数」被 Lua 合并成原子操作后：
 * 重复点赞只生效一次；100 并发点赞也只会产生 1 条关系、1 次计数。
 */
class InteractServiceTest extends AbstractIntegrationTest {

    @Autowired
    private InteractService interactService;
    @Autowired
    private StringRedisTemplate redis;

    private void resetLike(long noteId) {
        redis.delete(RedisKeys.like(noteId));
        redis.delete(RedisKeys.likeCount(noteId));
    }

    @Test
    @DisplayName("重复点赞：第二次被拒，关系与计数都只 +1")
    void duplicateLikeCountsOnce() {
        long noteId = 1L, userId = 5L;
        resetLike(noteId);

        assertThat(interactService.like(noteId, userId).getCode()).isEqualTo(200);
        assertThat(interactService.like(noteId, userId).getCode()).isNotEqualTo(200);

        assertThat(redis.opsForSet().size(RedisKeys.like(noteId))).isEqualTo(1L);
        assertThat(redis.opsForValue().get(RedisKeys.likeCount(noteId))).isEqualTo("1");
    }

    @Test
    @DisplayName("取消点赞后可以再次点赞")
    void unlikeThenLikeAgain() {
        long noteId = 2L, userId = 5L;
        resetLike(noteId);

        assertThat(interactService.like(noteId, userId).getCode()).isEqualTo(200);
        assertThat(interactService.unlike(noteId, userId).getCode()).isEqualTo(200);
        assertThat(redis.opsForSet().size(RedisKeys.like(noteId))).isEqualTo(0L);
        assertThat(redis.opsForValue().get(RedisKeys.likeCount(noteId))).isEqualTo("0");

        assertThat(interactService.like(noteId, userId).getCode()).isEqualTo(200);
        assertThat(redis.opsForSet().size(RedisKeys.like(noteId))).isEqualTo(1L);
    }

    @Test
    @DisplayName("100 并发点赞同一个 (note,user)：只有 1 次成功，SCARD 与 count 都是 1")
    void concurrentLikesProduceSingleRelation() throws Exception {
        long noteId = 3L, userId = 5L;
        resetLike(noteId);

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return interactService.like(noteId, userId).getCode();
            }));
        }
        start.countDown();

        int success = 0;
        for (Future<Integer> f : futures) {
            if (f.get() == 200) {
                success++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(redis.opsForSet().size(RedisKeys.like(noteId))).isEqualTo(1L);
        assertThat(redis.opsForValue().get(RedisKeys.likeCount(noteId))).isEqualTo("1");
    }

    @Test
    @DisplayName("并发取消点赞：计数不会被减成负数")
    void concurrentUnlikesNeverGoNegative() throws Exception {
        long noteId = 4L, userId = 5L;
        resetLike(noteId);
        interactService.like(noteId, userId);

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result<Void>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return interactService.unlike(noteId, userId);
            }));
        }
        start.countDown();
        for (Future<Result<Void>> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(redis.opsForSet().size(RedisKeys.like(noteId))).isEqualTo(0L);
        assertThat(Long.parseLong(redis.opsForValue().get(RedisKeys.likeCount(noteId)))).isGreaterThanOrEqualTo(0L);
    }
}
