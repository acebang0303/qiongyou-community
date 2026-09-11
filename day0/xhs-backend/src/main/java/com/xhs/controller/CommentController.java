package com.xhs.controller;

import com.xhs.common.Result;
import com.xhs.service.CommentService;
import com.xhs.vo.CommentVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评论接口
 */
@RestController
@RequestMapping("/api/notes/{noteId}/comments")
public class CommentController {

    @Autowired
    private CommentService commentService;

    /** 评论列表 */
    @GetMapping
    public Result<List<CommentVO>> list(@PathVariable Long noteId) {
        return Result.ok(commentService.listByNote(noteId));
    }

    /** 发表评论 */
    @PostMapping
    public Result<Void> add(
            @PathVariable Long noteId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return commentService.add(noteId, userId, body.get("content"));
    }
}
