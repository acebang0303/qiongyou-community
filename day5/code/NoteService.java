package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xhs.common.RedisKeys;
import com.xhs.entity.Follow;
import com.xhs.entity.Note;
import com.xhs.mapper.FollowMapper;
import com.xhs.mapper.NoteMapper;
import com.xhs.vo.NoteVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Day5 版本（在 Day3/Day4 基础上）：缓存治理三件套，三味药汇聚在同一个 rebuildWithLock
 * - 穿透（查不存在的 id）：空对象缓存 60 秒
 * - 击穿（热点 Key 过期）：分布式锁互斥重建（重点）
 * - 雪崩（大量 Key 同时过期）：TTL 随机抖动 0~5 分钟
 *
 * 复现基线见 commit-files/xhs-pp-java1/day2 的 detail（仅 Cache Aside、无加固）。
 */
@Slf4j
@Service
public class NoteService {

    /** 基础缓存时长（秒）：30分钟 */
    private static final long BASE_TTL_SECONDS = 1800;
    /** 随机抖动上限（秒）：防雪崩 */
    private static final long JITTER_SECONDS = 300;
    /** 空对象缓存时长（秒）：防穿透 */
    private static final long NULL_TTL_SECONDS = 60;
    /** 重建锁超时（秒）：防持锁线程崩溃导致死锁 */
    private static final long LOCK_TTL_SECONDS = 10;

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** 推荐页：最新笔记 */
    public List<NoteVO> latest(int page, int size, Long viewerId) {
        List<NoteVO> list = noteMapper.selectLatest((page - 1) * size, size);
        fillStatus(list, viewerId);
        mergeCounts(list);
        return list;
    }

    /** 关注页（Day7 改造） */
    public List<NoteVO> followFeed(Long userId, int page, int size, Long viewerId) {
        List<NoteVO> list = noteMapper.selectFollowFeed(userId, (page - 1) * size, size);
        fillStatus(list, viewerId);
        mergeCounts(list);
        return list;
    }

    /** 热门榜单（Day7 改造） */
    public List<NoteVO> hot(Long viewerId) {
        List<NoteVO> list = noteMapper.selectHot();
        fillStatus(list, viewerId);
        mergeCounts(list);
        return list;
    }

    /** 搜索（Day8 改造） */
    public List<NoteVO> search(String keyword, int page, int size, Long viewerId) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }
        List<NoteVO> list = noteMapper.selectSearch(keyword.trim(), (page - 1) * size, size);
        fillStatus(list, viewerId);
        mergeCounts(list);
        return list;
    }

    /**
     * ★ Day5 改造：缓存 → 未命中走分布式锁互斥重建
     */
    public NoteVO detail(Long id, Long viewerId) {
        NoteVO vo = getFromCache(id);
        if (vo == null) {
            vo = rebuildWithLock(id);
        }
        // 空对象缓存（id为null）表示笔记不存在
        if (vo == null || vo.getId() == null) {
            return null;
        }
        fillStatus(Collections.singletonList(vo), viewerId);
        mergeCounts(Collections.singletonList(vo));
        return vo;
    }

    /** 读缓存 */
    private NoteVO getFromCache(Long id) {
        return (NoteVO) redisTemplate.opsForValue().get(RedisKeys.note(id));
    }

    /**
     * ★ 分布式锁互斥重建
     * 抢到锁 → 双重检查 → 查库 → 回填（空对象/随机TTL）→ 释放锁
     * 没抢到 → 短暂等待后重读缓存（此时大概率已重建完成）
     */
    private NoteVO rebuildWithLock(Long id) {
        String lockKey = RedisKeys.noteLock(id);
        // 【防击穿】抢锁：只有抢到的线程回源重建，其余线程等待后重读缓存
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(locked)) {
            // 没抢到锁：等一下再读缓存，此时大概率已被别人重建好
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            NoteVO vo = getFromCache(id);
            // 极端情况下仍为空，直接回源（由教师讲解此处取舍）
            return vo != null ? vo : noteMapper.selectDetail(id);
        }

        try {
            // 双重检查：可能在等锁期间缓存已被别人重建，避免重复回源
            NoteVO vo = getFromCache(id);
            if (vo != null) {
                return vo;
            }
            vo = noteMapper.selectDetail(id);
            if (vo == null) {
                // 【防穿透】查库为空也缓存一个空对象（短 TTL），读取侧用 getId()==null 识别
                redisTemplate.opsForValue()
                        .set(RedisKeys.note(id), new NoteVO(), NULL_TTL_SECONDS, TimeUnit.SECONDS);
                return null;
            }
            // 【防雪崩】TTL = 基础值 + 随机抖动，把大量 Key 的过期时刻打散
            long ttl = BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(JITTER_SECONDS);
            redisTemplate.opsForValue().set(RedisKeys.note(id), vo, ttl, TimeUnit.SECONDS);
            return vo;
        } finally {
            // 简化版释放：生产环境建议用 Lua "比较值再删除" 防止误删别人的锁
            redisTemplate.delete(lockKey);
        }
    }

    /** 某个用户发布的笔记 */
    public List<NoteVO> notesByUser(Long userId, Long viewerId) {
        List<NoteVO> list = noteMapper.selectByUser(userId);
        fillStatus(list, viewerId);
        mergeCounts(list);
        return list;
    }

    /** 发布笔记 */
    public Long publish(Note note, Long userId) {
        if (!StringUtils.hasText(note.getTitle()) || !StringUtils.hasText(note.getContent())) {
            throw new IllegalArgumentException("标题和正文不能为空");
        }
        note.setId(null);
        note.setUserId(userId);
        note.setLikeCount(0);
        note.setCommentCount(0);
        note.setFavoriteCount(0);
        noteMapper.insert(note);
        return note.getId();
    }

    /** 点赞/收藏状态从 Redis 判断（Day3 改造） */
    private void fillStatus(List<NoteVO> list, Long viewerId) {
        if (list == null || list.isEmpty()) {
            return;
        }
        if (viewerId != null) {
            Set<Long> likedNotes = new HashSet<>();
            Set<Long> favoritedNotes = new HashSet<>();
            for (NoteVO vo : list) {
                if (Boolean.TRUE.equals(redisTemplate.opsForSet()
                        .isMember(RedisKeys.like(vo.getId()), viewerId.toString()))) {
                    likedNotes.add(vo.getId());
                }
                if (Boolean.TRUE.equals(redisTemplate.opsForSet()
                        .isMember(RedisKeys.favorite(vo.getId()), viewerId.toString()))) {
                    favoritedNotes.add(vo.getId());
                }
            }
            List<Long> authorIds = list.stream().map(NoteVO::getUserId).distinct().collect(Collectors.toList());
            Set<Long> followedAuthors = followMapper.selectList(
                    new LambdaQueryWrapper<Follow>()
                            .eq(Follow::getUserId, viewerId)
                            .in(Follow::getFollowUserId, authorIds))
                    .stream().map(Follow::getFollowUserId).collect(Collectors.toSet());

            for (NoteVO vo : list) {
                vo.setLiked(likedNotes.contains(vo.getId()));
                vo.setFavorited(favoritedNotes.contains(vo.getId()));
                vo.setFollowed(followedAuthors.contains(vo.getUserId()));
            }
        }
    }

    /** 点赞数/收藏数优先取 Redis（Day3 改造） */
    private void mergeCounts(List<NoteVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (NoteVO vo : list) {
            Object likeCount = redisTemplate.opsForValue().get(RedisKeys.likeCount(vo.getId()));
            if (likeCount != null) {
                vo.setLikeCount(Integer.parseInt(likeCount.toString()));
            }
            Object favoriteCount = redisTemplate.opsForValue().get(RedisKeys.favoriteCount(vo.getId()));
            if (favoriteCount != null) {
                vo.setFavoriteCount(Integer.parseInt(favoriteCount.toString()));
            }
        }
    }
}
