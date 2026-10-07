package com.xhs.service;

import com.xhs.common.RedisKeys;
import com.xhs.mapper.FollowMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Day7：关注页 Feed（推模式 / 写扩散）
 *
 * ZSet Key: feed:{userId}
 *   member = noteId
 *   score  = 发布时间戳（毫秒），按 score 倒序即时间线
 *
 * 发布笔记 → 推送到所有粉丝的收件箱
 * 读取关注页 → ZREVRANGE 分页
 */
@Service
public class FeedService {

    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 发布笔记后调用：推给作者的每个粉丝 */
    public void pushNote(Long noteId, Long authorId) {
        List<Long> fans = followMapper.selectFollowerIds(authorId);
        if (fans == null || fans.isEmpty()) {
            return;
        }
        double score = System.currentTimeMillis();
        for (Long fanId : fans) {
            stringRedisTemplate.opsForZSet().add(RedisKeys.feed(fanId), noteId.toString(), score);
        }
    }

    /** 分页读取某用户的收件箱（最新在前），返回笔记ID列表 */
    public List<Long> feedIds(Long userId, int page, int size) {
        long start = (long) (page - 1) * size;
        long end = start + size - 1;
        Set<String> members = stringRedisTemplate.opsForZSet()
                .reverseRange(RedisKeys.feed(userId), start, end);
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>(members.size());
        for (String m : members) {
            ids.add(Long.parseLong(m));
        }
        return ids;
    }
}
