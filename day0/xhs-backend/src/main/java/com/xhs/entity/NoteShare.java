package com.xhs.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_note_share")
public class NoteShare {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long noteId;
    private Long userId;
    private LocalDateTime createTime;

    /** 参数顺序与需求骨架一致：(noteId, userId) */
    public NoteShare(Long noteId, Long userId) {
        this.noteId = noteId;
        this.userId = userId;
    }
}
