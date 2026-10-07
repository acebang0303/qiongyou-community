package com.qiongyou.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Day9：收藏事件消息
 * Redis 更新成功后发送，由 FavoriteConsumer 异步落库
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteEvent {

    private Long noteId;
    private Long userId;
    /** true=收藏，false=取消收藏 */
    private boolean favorited;
}
