package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xhs.common.RedisKeys;
import com.xhs.entity.Follow;
import com.xhs.entity.Note;
import com.xhs.mapper.FollowMapper;
import com.xhs.mapper.NoteMapper;
import com.xhs.vo.NoteVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Day3 版本（在 Day2 基础上）：
 * - "是否点赞/收藏"改从 Redis Set 判断（SISMEMBER）
 * - 点赞数/收藏数优先读 Redis 计数，没有再回退数据库
 */
@Service
public class NoteService {

    private static final long NOTE_CACHE_MINUTES = 30;

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

    /** 笔记详情（Day2 缓存 + Day5 将加分布式锁） */
    public NoteVO detail(Long id, Long viewerId) {
        String key = RedisKeys.note(id);
        NoteVO vo = (NoteVO) redisTemplate.opsForValue().get(key);
        if (vo == null) {
            vo = noteMapper.selectDetail(id);
            if (vo != null) {
                redisTemplate.opsForValue().set(key, vo, NOTE_CACHE_MINUTES, TimeUnit.MINUTES);
            }
        }
        if (vo == null) {
            return null;
        }
        fillStatus(Collections.singletonList(vo), viewerId);
        mergeCounts(Collections.singletonList(vo));
        return vo;
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

    /**
     * ★ Day3 改造：点赞/收藏状态改从 Redis 判断
     * 关注关系仍走 MySQL（低频）
     */
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

    /**
     * ★ Day3 改造：点赞数/收藏数优先取 Redis，Redis 无数据时保留数据库值
     * （评论数暂仍来自数据库）
     */
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
