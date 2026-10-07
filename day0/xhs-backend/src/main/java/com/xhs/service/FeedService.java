package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xhs.common.RedisKeys;
import com.xhs.entity.Follow;
import com.xhs.mapper.FollowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Day7：关注页 Feed
 * ★ P1-9：加入大V推拉结合
 *
 * 发件箱 ZSet: feed:outbox:{authorId} —— 每个作者发布时只写这一条
 * 收件箱 ZSet: feed:{userId}         —— 普通作者的笔记在发布时推给每个粉丝
 *
 * - 普通作者（粉丝数 <= 阈值）：写自己 outbox + 推给所有粉丝的 inbox（推模式）
 * - 大V（粉丝数 > 阈值）：只写自己 outbox，不推（粉丝读关注页时来拉，拉模式）
 *
 * 读关注页 = 合并「自己收件箱」+「所关注大V的发件箱」，按 score(时间戳) 倒序分页。
 */
@Slf4j
@Service
public class FeedService {

    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 粉丝数超过该值的作者视为大V，走拉模式 */
    @Value("${xhs.feed.big-v-fans-threshold:1000}")
    private long bigVFansThreshold;

    /** 收件箱/发件箱保留的最大条数（★ P1-10） */
    @Value("${xhs.feed.keep:100}")
    private long feedKeep;

    /** 发布笔记后调用：写作者发件箱；非大V 再推给每个粉丝 */
    public void pushNote(Long noteId, Long authorId) {
        double score = System.currentTimeMillis();
        // 所有作者都只写自己的一条发件箱（大V 的粉丝读时从这里拉）
        String outboxKey = RedisKeys.feedOutbox(authorId);
        stringRedisTemplate.opsForZSet().add(outboxKey, noteId.toString(), score);
        trim(outboxKey);

        Long fansCount = followMapper.selectCount(
                new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, authorId));
        if (fansCount != null && fansCount > bigVFansThreshold) {
            log.info("作者 {} 粉丝数 {} 超过阈值 {}，走拉模式（不写粉丝收件箱）",
                    authorId, fansCount, bigVFansThreshold);
            return;
        }
        List<Long> fans = followMapper.selectFollowerIds(authorId);
        if (fans == null || fans.isEmpty()) {
            return;
        }
        for (Long fanId : fans) {
            String inboxKey = RedisKeys.feed(fanId);
            stringRedisTemplate.opsForZSet().add(inboxKey, noteId.toString(), score);
            trim(inboxKey);
        }
    }

    /** ★ P1-10：按排名删除最旧的，只保留最新 feedKeep 条（O(log N)） */
    private void trim(String key) {
        stringRedisTemplate.opsForZSet().removeRange(key, 0, -(feedKeep + 1));
    }

    /** 分页读取某用户的关注页（新→旧）：合并收件箱 + 所关注大V的发件箱 */
    public List<Long> feedIds(Long userId, int page, int size) {
        int need = page * size;
        Map<Long, Double> scoreById = new HashMap<>();
        // 1) 普通作者的推：自己收件箱
        collect(scoreById, RedisKeys.feed(userId), need);
        // 2) 大V的拉：所关注大V的发件箱
        List<Long> bigVIds = followMapper.selectBigVFolloweeIds(userId, bigVFansThreshold);
        if (bigVIds != null) {
            for (Long bigVId : bigVIds) {
                collect(scoreById, RedisKeys.feedOutbox(bigVId), need);
            }
        }
        // 3) 归并去重后按时间倒序
        List<Long> merged = new ArrayList<>(scoreById.keySet());
        merged.sort((a, b) -> Double.compare(scoreById.get(b), scoreById.get(a)));

        int from = (page - 1) * size;
        if (from >= merged.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(merged.subList(from, Math.min(from + size, merged.size())));
    }

    /** 取某 ZSet 的前 limit 条 (noteId → score) 并入结果，重复的保留先到的 */
    private void collect(Map<Long, Double> into, String key, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(key, 0, limit - 1);
        if (tuples == null || tuples.isEmpty()) {
            return;
        }
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null) {
                continue;
            }
            into.putIfAbsent(Long.parseLong(tuple.getValue()),
                    tuple.getScore() == null ? 0D : tuple.getScore());
        }
    }
}
