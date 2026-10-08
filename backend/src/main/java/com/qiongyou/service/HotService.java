package com.qiongyou.service;

import com.qiongyou.common.RedisKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 热点榜单
 *
 * ZSet Key: hot:notes
 *   member = noteId
 *   score  = 热度（点赞×1 + 评论×5 + 收藏×2，实时 ZINCRBY 累加）
 *
 * 读榜单：ZREVRANGE 0 9，一次命令完成"排序+分页"
 */
@Service
public class HotService {

    /** 热度权重 */
    public static final double WEIGHT_LIKE = 1;
    public static final double WEIGHT_FAVORITE = 2;
    public static final double WEIGHT_COMMENT = 5;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 累加热度（可为负，用于取消操作） */
    public void addHeat(Long noteId, double weight) {
        stringRedisTemplate.opsForZSet()
                .incrementScore(RedisKeys.HOT_NOTES, noteId.toString(), weight);
    }

    /** ★ P1-11：直接设置热度（覆盖写，定时衰减重算用） */
    public void setScore(Long noteId, double score) {
        stringRedisTemplate.opsForZSet()
                .add(RedisKeys.HOT_NOTES, noteId.toString(), score);
    }

    /** 热度前 N 的笔记ID（score 倒序） */
    public List<Long> topIds(int limit) {
        Set<String> members = stringRedisTemplate.opsForZSet()
                .reverseRange(RedisKeys.HOT_NOTES, 0, limit - 1);
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>(members.size());
        for (String m : members) {
            ids.add(Long.parseLong(m));
        }
        return ids;
    }

    /** 榜单是否为空（冷启动判断） */
    public boolean isEmpty() {
        Long size = stringRedisTemplate.opsForZSet().zCard(RedisKeys.HOT_NOTES);
        return size == null || size == 0;
    }
}
