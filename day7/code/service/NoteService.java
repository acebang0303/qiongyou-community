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
 * Day7 版本（在 Day5 基础上）：
 * - 关注页：ZSet Feed 收件箱（空则回退 MySQL 联表）
 * - 热门榜：ZSet 热度榜（空则回退 MySQL 聚合）
 * - 发布笔记：推送到粉丝收件箱
 * - 详情缓存/分布式锁逻辑保持不变
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
    @Autowired
    private FeedService feedService;
    @Autowired
    private HotService hotService;

    /** 推荐页：最新笔记 */
    public List<NoteVO> latest(int page, int size, Long viewerId) {
        List<NoteVO> list = noteMapper.selectLatest((page - 1) * size, size);
        fillStatus(list, viewerId);
        mergeCounts(list);
        return list;
    }

    /**
     * ★ Day7 改造：关注页走 ZSet Feed 收件箱
     * 收件箱为空（新用户/冷启动）时回退 MySQL 联表
     */
    public List<NoteVO> followFeed(Long userId, int page, int size, Long viewerId) {
        List<Long> ids = feedService.feedIds(userId, page, size);
        List<NoteVO> list;
        if (ids.isEmpty()) {
            list = noteMapper.selectFollowFeed(userId, (page - 1) * size, size);
        } else {
            list = noteMapper.selectByIds(ids);
        }
        fillStatus(list, viewerId);
        mergeCounts(list);
        return list;
    }

    /**
     * ★ Day7 改造：热门榜走 ZSet 热度榜
     * 榜单为空（冷启动）时回退 MySQL 聚合排序
     */
    public List<NoteVO> hot(Long viewerId) {
        List<NoteVO> list;
        if (hotService.isEmpty()) {
            list = noteMapper.selectHot();
        } else {
            list = noteMapper.selectByIds(hotService.topIds(10));
        }
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

    /** 缓存 → 未命中走分布式锁互斥重建（Day5 逻辑，保持不变） */
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

    /** 分布式锁互斥重建（Day5 逻辑，保持不变） */
    private NoteVO rebuildWithLock(Long id) {
        String lockKey = RedisKeys.noteLock(id);
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(locked)) {
            // 没抢到锁：等一下再读缓存
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
            // 双重检查：可能在等锁期间缓存已被别人重建
            NoteVO vo = getFromCache(id);
            if (vo != null) {
                return vo;
            }
            vo = noteMapper.selectDetail(id);
            if (vo == null) {
                // 防穿透：空对象也缓存一小段时间
                redisTemplate.opsForValue()
                        .set(RedisKeys.note(id), new NoteVO(), NULL_TTL_SECONDS, TimeUnit.SECONDS);
                return null;
            }
            // 防雪崩：TTL 加随机抖动
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

    /**
     * ★ Day7 改造：发布笔记后推送到所有粉丝的 Feed 收件箱
     */
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
        // ★ Day7：写扩散推送（大V场景可改为异步任务，此处同步演示）
        feedService.pushNote(note.getId(), userId);
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
