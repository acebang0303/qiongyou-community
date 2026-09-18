package com.xhs.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Day6：评论事件消息
 * 评论入库后发送，由 NotificationConsumer 给笔记作者发通知
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentEvent {

    private Long noteId;
    /** 笔记作者（被通知人） */
    private Long authorId;
    /** 评论人 */
    private Long userId;
    private String content;
}
