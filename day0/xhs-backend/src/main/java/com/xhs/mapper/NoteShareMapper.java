package com.xhs.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xhs.entity.NoteShare;

public interface NoteShareMapper extends BaseMapper<NoteShare> {

    /** 取消分享：按 (userId, noteId) 删除记录 */
    default int deleteByUserAndNote(Long userId, Long noteId) {
        return delete(new LambdaQueryWrapper<NoteShare>()
                .eq(NoteShare::getUserId, userId)
                .eq(NoteShare::getNoteId, noteId));
    }
}
