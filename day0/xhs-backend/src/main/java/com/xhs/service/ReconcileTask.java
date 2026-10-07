package com.xhs.service;

import com.xhs.common.RedisKeys;
import com.xhs.entity.NoteFavorite;
import com.xhs.entity.NoteLike;
import com.xhs.entity.NoteShare;
import com.xhs.mapper.NoteFavoriteMapper;
import com.xhs.mapper.NoteLikeMapper;
import com.xhs.mapper.NoteShareMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * P1-7：Redis 与 MySQL 互动数据对账（最终一致的兜底）
 *
 * 以 MySQL 为准，把互动关系与计数回填 Redis：
 *   like:{noteId} / like:count:{noteId}
 *   favorite:{noteId} / favorite:count:{noteId}
 *   share:{noteId} / share:count:{noteId}
 *
 * 策略是「只增不删」的增量回填：把 MySQL 里的成员 SADD 进 Redis，再把计数对齐为 Set 的实际基数。
 * 不整体重建（不 DEL）的原因：本项目是「Redis 先写 → MQ → MySQL」，对账跑的那一刻可能存在
 * 「已写 Redis、消息还在队列里」的关系，整体重建会把它抹掉；只增不删可避免误删在途数据。
 *
 * 局限：只补 Redis 缺的（种子数据/冷启动/Redis 被清空），**不处理**「Redis 多、MySQL 少」
 * （消息丢失或进了 DLQ）——那属于 MySQL 侧缺口，需人工依据 DLQ 另行处理。
 *
 * 一致性用 StringRedisTemplate：互动 Set 的成员是裸字符串（与 InteractService 的 Lua 写入一致）。
 */
@Slf4j
@Component
public class ReconcileTask {

    /** 启动 10 秒后跑第一次（顺带修复冷启动/种子数据），之后每 10 分钟一次（延迟可配，便于测试关停） */
    @Scheduled(initialDelayString = "${xhs.reconcile.initial-delay-ms:10000}",
            fixedDelayString = "${xhs.reconcile.fixed-delay-ms:600000}")
    public void reconcile() {
        long start = System.currentTimeMillis();
        int fixed = 0;
        fixed += reconcileType(
                groupByNote(noteLikeMapper.selectList(null), NoteLike::getNoteId, NoteLike::getUserId),
                RedisKeys::like, RedisKeys::likeCount, "点赞");
        fixed += reconcileType(
                groupByNote(noteFavoriteMapper.selectList(null), NoteFavorite::getNoteId, NoteFavorite::getUserId),
                RedisKeys::favorite, RedisKeys::favoriteCount, "收藏");
        fixed += reconcileType(
                groupByNote(noteShareMapper.selectList(null), NoteShare::getNoteId, NoteShare::getUserId),
                RedisKeys::share, RedisKeys::shareCount, "分享");
        log.info("【对账】完成，用时 {} ms，回填 {} 个不一致集合", System.currentTimeMillis() - start, fixed);
    }

    @Autowired
    private NoteLikeMapper noteLikeMapper;
    @Autowired
    private NoteFavoriteMapper noteFavoriteMapper;
    @Autowired
    private NoteShareMapper noteShareMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 按 noteId 分组：{noteId: [userId, ...]} */
    private <T> Map<Long, List<Long>> groupByNote(List<T> rows,
                                                  Function<T, Long> noteIdFn,
                                                  Function<T, Long> userIdFn) {
        Map<Long, List<Long>> map = new HashMap<>();
        for (T row : rows) {
            map.computeIfAbsent(noteIdFn.apply(row), k -> new ArrayList<>()).add(userIdFn.apply(row));
        }
        return map;
    }

    /** 回填一类互动；返回发生变化的集合数 */
    private int reconcileType(Map<Long, List<Long>> dbMap,
                             Function<Long, String> setKeyFn,
                             Function<Long, String> countKeyFn,
                             String label) {
        int fixed = 0;
        for (Map.Entry<Long, List<Long>> entry : dbMap.entrySet()) {
            Long noteId = entry.getKey();
            String setKey = setKeyFn.apply(noteId);
            Long sizeBefore = stringRedisTemplate.opsForSet().size(setKey);
            long before = sizeBefore == null ? 0 : sizeBefore;

            String[] members = entry.getValue().stream().map(String::valueOf).toArray(String[]::new);
            stringRedisTemplate.opsForSet().add(setKey, members);

            Long sizeAfter = stringRedisTemplate.opsForSet().size(setKey);
            long after = sizeAfter == null ? 0 : sizeAfter;
            // 计数以 Set 的实际基数为准（Set 是关系真相，接口返回的也是它）
            stringRedisTemplate.opsForValue().set(countKeyFn.apply(noteId), String.valueOf(after));

            if (after != before) {
                fixed++;
                log.info("【对账】{} noteId={} Redis 集合 {} → {}（按 MySQL 回填）", label, noteId, before, after);
            }
        }
        return fixed;
    }
}
