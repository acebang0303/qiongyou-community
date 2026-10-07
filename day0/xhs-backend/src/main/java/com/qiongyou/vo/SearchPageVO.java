package com.qiongyou.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ★ P1-16：搜索结果分页返回体
 * nextCursor 为 null 表示没有下一页；下次请求把它原样传回 cursor 参数
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchPageVO {

    private List<NoteVO> list;
    private String nextCursor;
}
