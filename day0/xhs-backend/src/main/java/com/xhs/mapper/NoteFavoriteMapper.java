package com.xhs.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xhs.entity.NoteFavorite;

public interface NoteFavoriteMapper extends BaseMapper<NoteFavorite> {

    /** 取消收藏：按 (userId, noteId) 删除记录 */
    default int deleteByUserAndNote(Long userId, Long noteId) {
        return delete(new LambdaQueryWrapper<NoteFavorite>()
                .eq(NoteFavorite::getUserId, userId)
                .eq(NoteFavorite::getNoteId, noteId));
    }
}
