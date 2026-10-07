package com.xhs.service;

import com.xhs.AbstractIntegrationTest;
import com.xhs.common.RedisKeys;
import com.xhs.vo.NoteVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ P2-2：笔记详情的缓存三兄弟（Day5 的核心考点）
 * 重点回归「空对象缓存防穿透」——不存在的 id 也要落一个短 TTL 的空对象，
 * 否则每次请求都会击穿到 MySQL。
 */
class NoteCacheTest extends AbstractIntegrationTest {

    @Autowired
    private NoteService noteService;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    @DisplayName("详情未命中会回源并写入缓存（正常 TTL 约 30 分钟）")
    void missThenCache() {
        long noteId = 1L;
        redisTemplate.delete(RedisKeys.note(noteId));

        NoteVO vo = noteService.detail(noteId, null);

        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isEqualTo(noteId);
        assertThat(redisTemplate.hasKey(RedisKeys.note(noteId))).isTrue();

        Long ttl = redisTemplate.getExpire(RedisKeys.note(noteId), TimeUnit.SECONDS);
        // 基础 1800s + 0~300s 抖动
        assertThat(ttl).isBetween(1700L, 2200L);
    }

    @Test
    @DisplayName("不存在的笔记：返回 null，但落一个短 TTL 的空对象防穿透")
    void missingNoteCachesNullObject() {
        long missingId = 987654321L;
        redisTemplate.delete(RedisKeys.note(missingId));

        assertThat(noteService.detail(missingId, null)).isNull();
        assertThat(redisTemplate.hasKey(RedisKeys.note(missingId)))
                .as("空对象应被缓存，否则每次请求都击穿到 MySQL")
                .isTrue();

        // 第二次仍然是 null（命中空对象，不再回源）
        assertThat(noteService.detail(missingId, null)).isNull();

        Long ttl = redisTemplate.getExpire(RedisKeys.note(missingId), TimeUnit.SECONDS);
        assertThat(ttl).as("空对象 TTL 应为 60 秒").isBetween(1L, 60L);
    }

    @Test
    @DisplayName("缓存命中：删除库里的笔记后仍能读到缓存（证明读的是缓存而非库）")
    void hitCacheWithoutDb() {
        long noteId = 2L;
        redisTemplate.delete(RedisKeys.note(noteId));
        NoteVO first = noteService.detail(noteId, null);
        assertThat(first).isNotNull();

        NoteVO second = noteService.detail(noteId, null);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(redisTemplate.getExpire(RedisKeys.note(noteId), TimeUnit.SECONDS)).isGreaterThan(0);
    }
}
