package com.qiongyou.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_follow")
public class Follow {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关注者（粉丝） */
    private Long userId;
    /** 被关注者 */
    private Long followUserId;
    private LocalDateTime createTime;
}
