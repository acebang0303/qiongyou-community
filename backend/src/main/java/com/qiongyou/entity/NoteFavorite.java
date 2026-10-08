package com.qiongyou.entity;

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
@TableName("t_note_favorite")
public class NoteFavorite {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long noteId;
    private LocalDateTime createTime;

    public NoteFavorite(Long userId, Long noteId) {
        this.userId = userId;
        this.noteId = noteId;
    }
}
