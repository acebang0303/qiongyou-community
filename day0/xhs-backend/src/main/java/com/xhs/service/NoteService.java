package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xhs.common.RedisKeys;
import com.xhs.config.RabbitConfig;
import com.xhs.dto.NoteEvent;
import com.xhs.entity.Follow;
import com.xhs.entity.Note;
import com.xhs.mapper.FollowMapper;
import com.xhs.mapper.NoteMapper;
import com.xhs.vo.NoteVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
 * Day8 版本（在 Day7 基础上）：
 * - 搜索：优先走 Elasticsearch，ES 异常或无结果时回退 MySQL LIKE（降级）
 * - 发布笔记：发送 NoteEvent 到 MQ，由 EsConsumer 异步写入 ES 索引
 * - 其余逻辑（缓存/分布式锁/Feed/热榜）保持不变
 */
@Slf4j
@Service
public class NoteService {

    private static final long BASE_TTL_SECONDS = 1800;
    private static final long JITTER_SECONDS = 300;
    private static final long NULL_TTL_SECONDS = 60;
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
    @Autowired
    private EsService esService;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 推荐页：最新笔记 */
    public List<NoteVO> latest(int page, int size, Long viewerId) {
        List<NoteVO> list = noteMapper.selectLatest((page - 1) * size, size);
        fillStatus(list, viewerId);
        mergeCounts(list);
        return list;
    }

    /** 关注页：ZSet Feed 收件箱（Day7 逻辑，保持不变） */
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

    /** 热门榜：ZSet 热度榜（Day7 逻辑，保持不变） */
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

    /**
     * ★ Day8 改造：搜索优先走 ES
     * ES 抛异常（服务不可用）→ 降级回退 MySQL LIKE，保证功能可用
     */
    public List<NoteVO> search(String keyword, int page, int size, Long viewerId) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }
        List<NoteVO> list;
        try {
            List<Long> ids = esService.searchIds(keyword.trim(), page, size);
            list = ids.isEmpty() ? Collections.emptyList() : noteMapper.selectByIds(ids);
        } catch (Exception e) {
            log.warn("ES 搜索失败，降级到 MySQL：{}", e.getMessage());
            list = noteMapper.selectSearch(keyword.trim(), (page - 1) * size, size);
        }
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
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            NoteVO vo = getFromCache(id);
            return vo != null ? vo : noteMapper.selectDetail(id);
        }

        try {
            NoteVO vo = getFromCache(id);
            if (vo != null) {
                return vo;
            }
            vo = noteMapper.selectDetail(id);
            if (vo == null) {
                redisTemplate.opsForValue()
                        .set(RedisKeys.note(id), new NoteVO(), NULL_TTL_SECONDS, TimeUnit.SECONDS);
                return null;
            }
            long ttl = BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(JITTER_SECONDS);
            redisTemplate.opsForValue().set(RedisKeys.note(id), vo, ttl, TimeUnit.SECONDS);
            return vo;
        } finally {
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
     * ★ Day8 改造：发布笔记后发送 NoteEvent，异步写入 ES 索引
     * （Day7 的 Feed 推送逻辑保留）
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
        feedService.pushNote(note.getId(), userId);
        // ★ Day8：发 MQ 异步建索引，发布主流程不等待 ES
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE,
                RabbitConfig.NOTE_ES_ROUTING_KEY, new NoteEvent(note.getId()));
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
