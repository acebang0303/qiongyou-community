package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xhs.entity.Follow;
import com.xhs.entity.Note;
import com.xhs.entity.NoteFavorite;
import com.xhs.entity.NoteLike;
import com.xhs.mapper.FollowMapper;
import com.xhs.mapper.NoteFavoriteMapper;
import com.xhs.mapper.NoteLikeMapper;
import com.xhs.mapper.NoteMapper;
import com.xhs.vo.NoteVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 笔记服务（基线版：全部直查 MySQL）
 *
 * Day2 起将逐步优化：
 * - detail：增加 Redis 缓存（Cache Aside）
 * - hot / followFeed：改为 Redis ZSet
 * - search：改为 Elasticsearch
 */
@Service
public class NoteService {

    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private NoteLikeMapper likeMapper;
    @Autowired
    private NoteFavoriteMapper favoriteMapper;
    @Autowired
    private FollowMapper followMapper;

    /** 推荐页：最新笔记 */
    public List<NoteVO> latest(int page, int size, Long viewerId) {
        List<NoteVO> list = noteMapper.selectLatest((page - 1) * size, size);
        fillStatus(list, viewerId);
        return list;
    }

    /** 关注页：我关注的人发布的笔记（MySQL 子查询，Day7 优化为 ZSet Feed） */
    public List<NoteVO> followFeed(Long userId, int page, int size, Long viewerId) {
        List<NoteVO> list = noteMapper.selectFollowFeed(userId, (page - 1) * size, size);
        fillStatus(list, viewerId);
        return list;
    }

    /** 热门榜单（MySQL 聚合排序，Day7 优化为 Redis ZSet） */
    public List<NoteVO> hot(Long viewerId) {
        List<NoteVO> list = noteMapper.selectHot();
        fillStatus(list, viewerId);
        return list;
    }

    /** 搜索（MySQL LIKE，Day8 优化为 Elasticsearch） */
    public List<NoteVO> search(String keyword, int page, int size, Long viewerId) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }
        List<NoteVO> list = noteMapper.selectSearch(keyword.trim(), (page - 1) * size, size);
        fillStatus(list, viewerId);
        return list;
    }

    /** 笔记详情（Day2 优化为 Redis 缓存） */
    public NoteVO detail(Long id, Long viewerId) {
        NoteVO vo = noteMapper.selectDetail(id);
        if (vo == null) {
            return null;
        }
        fillStatus(Collections.singletonList(vo), viewerId);
        return vo;
    }

    /** 某个用户发布的笔记 */
    public List<NoteVO> notesByUser(Long userId, Long viewerId) {
        List<NoteVO> list = noteMapper.selectByUser(userId);
        fillStatus(list, viewerId);
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
     * 批量填充互动状态（是否点赞/收藏/关注作者）
     * 基线版：3 次 IN 查询。量大后属于典型的"可合并进缓存"的查询，留给学生观察
     */
    private void fillStatus(List<NoteVO> list, Long viewerId) {
        if (list == null || list.isEmpty() || viewerId == null) {
            return;
        }
        List<Long> noteIds = list.stream().map(NoteVO::getId).collect(Collectors.toList());
        List<Long> authorIds = list.stream().map(NoteVO::getUserId).distinct().collect(Collectors.toList());

        Set<Long> likedNotes = likeMapper.selectList(
                new LambdaQueryWrapper<NoteLike>()
                        .eq(NoteLike::getUserId, viewerId)
                        .in(NoteLike::getNoteId, noteIds))
                .stream().map(NoteLike::getNoteId).collect(Collectors.toSet());

        Set<Long> favoritedNotes = favoriteMapper.selectList(
                new LambdaQueryWrapper<NoteFavorite>()
                        .eq(NoteFavorite::getUserId, viewerId)
                        .in(NoteFavorite::getNoteId, noteIds))
                .stream().map(NoteFavorite::getNoteId).collect(Collectors.toSet());

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
