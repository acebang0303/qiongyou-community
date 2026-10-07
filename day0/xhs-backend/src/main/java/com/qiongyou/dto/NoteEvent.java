package com.qiongyou.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Day8：笔记索引事件消息
 * 发布笔记成功后发送，由 EsConsumer 异步写入 Elasticsearch
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteEvent {

    private Long noteId;
}
