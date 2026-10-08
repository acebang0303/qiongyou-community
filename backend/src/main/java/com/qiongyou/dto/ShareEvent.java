package com.qiongyou.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Day9：分享事件消息
 * Redis 更新成功后发送，由 ShareConsumer 异步落库
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShareEvent {

    private Long noteId;
    private Long userId;
    /** true=分享，false=取消分享 */
    private boolean shared;
}
