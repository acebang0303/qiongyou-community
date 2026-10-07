package com.qiongyou.controller;

import com.qiongyou.common.Result;
import com.qiongyou.common.UserContext;
import com.qiongyou.entity.Note;
import com.qiongyou.service.NoteService;
import com.qiongyou.vo.NoteVO;
import com.qiongyou.vo.SearchPageVO;
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
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(noteService.latest(page, size, UserContext.getUserId()));
    }

    /** 关注页：我关注的人的笔记（需要登录） */
    @GetMapping("/follow")
    public Result<List<NoteVO>> follow(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long viewerId = UserContext.getUserId();
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
    public Result<List<NoteVO>> hot() {
        Long viewerId = UserContext.getUserId();
        try {
            return Result.ok(noteService.hot(viewerId));
        } catch (Exception e) {
            log.error("热门榜异常，降级返回最新列表：{}", e.getMessage());
            return Result.ok(noteService.latest(1, 10, viewerId));
        }
    }

    /** 关键词搜索（Day8 起服务端优先走 ES；★ P1-16 游标分页） */
    @GetMapping("/search")
    public Result<SearchPageVO> search(
            @RequestParam String keyword,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(noteService.search(keyword, size, cursor, UserContext.getUserId()));
    }

    /** 笔记详情 */
    @GetMapping("/{id}")
    public Result<NoteVO> detail(@PathVariable Long id) {
        NoteVO vo = noteService.detail(id, UserContext.getUserId());
        if (vo == null) {
            return Result.fail(404, "笔记不存在");
        }
        return Result.ok(vo);
    }

    /** 发布笔记 */
    @PostMapping
    public Result<Long> publish(@RequestBody Note note) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return Result.ok(noteService.publish(note, userId));
    }
}
