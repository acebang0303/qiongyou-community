package com.xhs.controller;

import com.xhs.common.Result;
import com.xhs.common.UserContext;
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

    /**
     * 评论列表（游标分页，新→旧）
     * lastId 缺省表示第一页；前端用「上一页最后一条的 id」作为下一页游标
     */
    @GetMapping
    public Result<List<CommentVO>> list(
            @PathVariable Long noteId,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(commentService.listByNote(noteId, lastId, size));
    }

    /** 发表评论 */
    @PostMapping
    public Result<Void> add(@PathVariable Long noteId, @RequestBody Map<String, String> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return commentService.add(noteId, userId, body.get("content"));
    }
}
