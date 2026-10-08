package com.qiongyou.service;

import com.qiongyou.common.RedisKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 热门话题榜
 *
 * ZSet Key: hot:tags
 *   member = 标签文本（如 三亚）
 *   score  = 出现次数（发布笔记时 ZINCRBY 累加）
 *
 * 读榜单：ZREVRANGE 0 9 WITHSCORES，一次命令完成"排序 + 取前 N"
 */
@Service
public class TagService {

    /** 标签 ZSet 用 String 序列化读写，避免 value 被 JSON 序列化带上引号 */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 标签热度 +1（tags 为空时直接返回） */
    public void addHeat(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        for (String tag : tags) {
            stringRedisTemplate.opsForZSet().incrementScore(RedisKeys.hotTags(), tag, 1);
        }
    }

    /** 热度前 N 的标签：[{tag: "三亚", score: 12}, ...] */
    public List<Map<String, Object>> topTags(int n) {
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(RedisKeys.hotTags(), 0, n - 1);
        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tag", tuple.getValue());
            item.put("score", tuple.getScore().longValue());
            list.add(item);
        }
        return list;
    }

    /** 榜单是否为空（冷启动判断） */
    public boolean isEmpty() {
        Long size = stringRedisTemplate.opsForZSet().zCard(RedisKeys.hotTags());
        return size == null || size == 0;
    }
}
