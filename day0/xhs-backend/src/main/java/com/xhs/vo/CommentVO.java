package com.xhs.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论视图对象：评论内容 + 评论人信息
 */
@Data
public class CommentVO {

    private Long id;
    private Long userId;
    private String nickname;
    private String avatar;
    private String content;
    private LocalDateTime createTime;
}
