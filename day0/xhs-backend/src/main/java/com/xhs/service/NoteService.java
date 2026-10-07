package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xhs.common.RedisKeys;
import com.xhs.common.RedisLock;
import com.xhs.common.TransactionHelper;
import com.xhs.config.RabbitConfig;
import com.xhs.dto.NoteEvent;
import com.xhs.entity.Follow;
import com.xhs.entity.Note;
import com.xhs.mapper.FollowMapper;
import com.xhs.mapper.NoteMapper;
import com.xhs.vo.NoteVO;
import com.xhs.vo.SearchPageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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
    /** ★ P3-5：没抢到锁时的轮询次数与间隔（合计约 100ms），避免只睡一次就直接查库 */
    private static final int LOCK_WAIT_RETRIES = 5;
    private static final long LOCK_WAIT_INTERVAL_MS = 20;

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    /** 互动类 Set/计数用 String 序列化读写，必须与 InteractService 的 Lua 写入侧成对 */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisLock redisLock;
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

    /** 关注页：ZSet Feed 收件箱（Day7 逻辑，Redis 异常时降级走 MySQL） */
    public List<NoteVO> followFeed(Long userId, int page, int size, Long viewerId) {
        List<NoteVO> list;
        try {
            List<Long> ids = feedService.feedIds(userId, page, size);
            if (ids.isEmpty()) {
                list = noteMapper.selectFollowFeed(userId, (page - 1) * size, size);
            } else {
                list = noteMapper.selectByIds(ids);
            }
        } catch (Exception e) {
            log.warn("Feed 服务异常，降级走 MySQL", e);
            list = noteMapper.selectFollowFeed(userId, (page - 1) * size, size);
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
     * ★ P1-16：改游标分页（search_after），返回 {list, nextCursor}
     * ES 抛异常（服务不可用）→ 降级回退 MySQL LIKE（单页，无游标）
     */
    public SearchPageVO search(String keyword, int size, String cursor, Long viewerId) {
        if (!StringUtils.hasText(keyword)) {
            return new SearchPageVO(Collections.emptyList(), null);
        }
        List<NoteVO> list;
        String nextCursor = null;
        try {
            EsService.Page page = esService.searchPage(keyword.trim(), size, cursor);
            list = page.getIds().isEmpty() ? Collections.emptyList() : noteMapper.selectByIds(page.getIds());
            nextCursor = page.getNextCursor();
        } catch (Exception e) {
            log.warn("ES 搜索失败，降级到 MySQL：{}", e.getMessage());
            // 降级走 MySQL 时无游标语义，只返回第一页
            list = noteMapper.selectSearch(keyword.trim(), 0, size);
        }
        fillStatus(list, viewerId);
        mergeCounts(list);
        return new SearchPageVO(list, nextCursor);
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

    /**
     * 分布式锁互斥重建（Day5 逻辑）
     * ★ P3-2：锁值改为唯一 token，释放走 Lua 比对归属（避免误删别人的锁）
     * ★ P3-5：没抢到锁改为短暂轮询等别人重建，而不是睡一次就直接回源查库
     */
    private NoteVO rebuildWithLock(Long id) {
        String lockKey = RedisKeys.noteLock(id);
        String token = redisLock.tryLock(lockKey, LOCK_TTL_SECONDS);

        if (token == null) {
            return waitForCacheOrLoad(id);
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
            redisLock.unlock(lockKey, token);
        }
    }

    /** 没抢到锁：短暂轮询等持锁者把缓存建好；始终没有则兜底查库 */
    private NoteVO waitForCacheOrLoad(Long id) {
        for (int i = 0; i < LOCK_WAIT_RETRIES; i++) {
            try {
                Thread.sleep(LOCK_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            NoteVO cached = getFromCache(id);
            if (cached != null) {
                return cached;
            }
        }
        return noteMapper.selectDetail(id);
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
     * ★ P0-4：加事务；Feed 推送(Redis) 与 MQ 发送移到提交后，避免回滚留下幽灵数据/消息
     */
    @Transactional
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
        Long noteId = note.getId();
        TransactionHelper.afterCommit(() -> {
            feedService.pushNote(noteId, userId);
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE,
                    RabbitConfig.NOTE_ES_ROUTING_KEY, new NoteEvent(noteId),
                    new CorrelationData("note.es:" + noteId));
        });
        return noteId;
    }

    /**
     * 点赞/收藏状态从 Redis 判断（Day3 改造）
     * ★ P3-1：改用 pipeline 批量取，把「每篇 2 次 SISMEMBER」的 N+1 往返压成 1 次往返
     */
    @SuppressWarnings("unchecked")
    private void fillStatus(List<NoteVO> list, Long viewerId) {
        if (list == null || list.isEmpty()) {
            return;
        }
        if (viewerId != null) {
            Set<Long> likedNotes = new HashSet<>();
            Set<Long> favoritedNotes = new HashSet<>();
            try {
                String uid = viewerId.toString();
                List<Object> results = stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
                    @Override
                    public <K, V> Object execute(RedisOperations<K, V> operations) {
                        SetOperations<K, V> setOps = operations.opsForSet();
                        for (NoteVO vo : list) {
                            setOps.isMember((K) RedisKeys.like(vo.getId()), (V) uid);
                            setOps.isMember((K) RedisKeys.favorite(vo.getId()), (V) uid);
                        }
                        return null; // 返回值由 pipeline 的结果列表给出
                    }
                });
                // 结果按提交顺序返回：每篇对应 [isMember(like), isMember(favorite)]
                for (int i = 0; i < list.size() && i * 2 + 1 < results.size(); i++) {
                    Long noteId = list.get(i).getId();
                    if (Boolean.TRUE.equals(results.get(i * 2))) {
                        likedNotes.add(noteId);
                    }
                    if (Boolean.TRUE.equals(results.get(i * 2 + 1))) {
                        favoritedNotes.add(noteId);
                    }
                }
            } catch (Exception e) {
                // Redis 故障时跳过点赞/收藏状态，保留默认值
                log.warn("读取互动状态失败，跳过：{}", e.getMessage());
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

    /**
     * 点赞数/收藏数/分享数优先取 Redis（Day3 改造；★ P1-2 补 shareCount）
     * ★ P3-1：改用 MGET 一次取回，把「每篇 3 次 GET」的 N+1 往返压成 1 次往返
     */
    private void mergeCounts(List<NoteVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>(list.size() * 3);
            for (NoteVO vo : list) {
                keys.add(RedisKeys.likeCount(vo.getId()));
                keys.add(RedisKeys.favoriteCount(vo.getId()));
                keys.add(RedisKeys.shareCount(vo.getId()));
            }
            List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);
            if (values == null) {
                return;
            }
            // MGET 结果与 keys 一一对应：每篇占连续三个槽位
            for (int i = 0; i < list.size() && i * 3 + 2 < values.size(); i++) {
                NoteVO vo = list.get(i);
                String likeCount = values.get(i * 3);
                String favoriteCount = values.get(i * 3 + 1);
                String shareCount = values.get(i * 3 + 2);
                if (likeCount != null) {
                    vo.setLikeCount(Integer.parseInt(likeCount));
                }
                if (favoriteCount != null) {
                    vo.setFavoriteCount(Integer.parseInt(favoriteCount));
                }
                if (shareCount != null) {
                    vo.setShareCount(Integer.parseInt(shareCount));
                }
            }
        } catch (Exception e) {
            // Redis 故障时保留 MySQL 里的计数
            log.warn("读取 Redis 计数失败，使用 MySQL 计数：{}", e.getMessage());
        }
    }
}
