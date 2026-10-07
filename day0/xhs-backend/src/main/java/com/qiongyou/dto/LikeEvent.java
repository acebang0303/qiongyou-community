package com.qiongyou.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Day6：点赞事件消息
 * Redis 更新成功后发送，由 LikeConsumer 异步落库
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LikeEvent {

    private Long noteId;
    private Long userId;
    /** true=点赞，false=取消点赞 */
    private boolean liked;
}
