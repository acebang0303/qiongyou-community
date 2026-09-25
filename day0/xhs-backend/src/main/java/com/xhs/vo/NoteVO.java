package com.xhs.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 笔记视图对象：笔记信息 + 作者信息 + 当前用户的互动状态
 */
@Data
public class NoteVO {

    private Long id;
    private Long userId;
    private String title;
    private String content;
    private String cover;
    private String tags;
    private Integer likeCount;
    private Integer commentCount;
    private Integer favoriteCount;
    /** 分享数：Redis 优先，Redis 无数据时回退 t_note.share_count */
    private Integer shareCount = 0;
    private LocalDateTime createTime;

    /** 作者昵称 */
    private String authorName;
    /** 作者头像 */
    private String authorAvatar;

    /** 当前用户是否已点赞 */
    private Boolean liked = false;
    /** 当前用户是否已收藏 */
    private Boolean favorited = false;
    /** 当前用户是否已关注作者 */
    private Boolean followed = false;
}
