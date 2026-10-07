package com.xhs.controller;

import com.xhs.common.Result;
import com.xhs.entity.Note;
import com.xhs.service.NoteService;
import com.xhs.vo.NoteVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Day8 版本：热门榜接口增加降级兜底
 * Redis 异常时不再直接报错，而是降级返回"最新笔记"，保证首页可用
 */
@Slf4j
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    @Autowired
    private NoteService noteService;

    /** 推荐页：最新笔记 */
    @GetMapping("/list")
    public Result<List<NoteVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader(value = "X-User-Id", required = false) Long viewerId) {
        return Result.ok(noteService.latest(page, size, viewerId));
    }

    /** 关注页：我关注的人的笔记（需要登录） */
    @GetMapping("/follow")
    public Result<List<NoteVO>> follow(
            @RequestHeader(value = "X-User-Id", required = false) Long viewerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (viewerId == null) {
            return Result.fail(401, "请先登录");
        }
        return Result.ok(noteService.followFeed(viewerId, page, size, viewerId));
    }

    /**
     * ★ Day8 改造：热门榜降级
     * 依赖组件（Redis/DB）异常时，降级返回最新笔记列表，而不是抛 500
     */
    @GetMapping("/hot")
    public Result<List<NoteVO>> hot(
            @RequestHeader(value = "X-User-Id", required = false) Long viewerId) {
        try {
            return Result.ok(noteService.hot(viewerId));
        } catch (Exception e) {
            log.error("热门榜异常，降级返回最新列表：{}", e.getMessage());
            return Result.ok(noteService.latest(1, 10, viewerId));
        }
    }

    /** 关键词搜索（Day8 起服务端优先走 ES） */
    @GetMapping("/search")
    public Result<List<NoteVO>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader(value = "X-User-Id", required = false) Long viewerId) {
        return Result.ok(noteService.search(keyword, page, size, viewerId));
    }

    /** 笔记详情 */
    @GetMapping("/{id}")
    public Result<NoteVO> detail(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long viewerId) {
        NoteVO vo = noteService.detail(id, viewerId);
        if (vo == null) {
            return Result.fail(404, "笔记不存在");
        }
        return Result.ok(vo);
    }

    /** 发布笔记 */
    @PostMapping
    public Result<Long> publish(
            @RequestBody Note note,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return Result.ok(noteService.publish(note, userId));
    }
}
