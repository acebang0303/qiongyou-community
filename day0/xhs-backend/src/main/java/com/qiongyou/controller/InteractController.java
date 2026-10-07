package com.qiongyou.controller;

import com.qiongyou.common.Result;
import com.qiongyou.common.UserContext;
import com.qiongyou.service.InteractService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 互动接口：点赞 / 收藏
 *
 * Day1 压测重点接口：POST /api/notes/{id}/like
 * （基线版同步写库，Day3 起优化）
 * P0-2：登录身份统一从 UserContext 取
 */
@RestController
@RequestMapping("/api/notes")
public class InteractController {

    @Autowired
    private InteractService interactService;

    /** 点赞 */
    @PostMapping("/{id}/like")
    public Result<Void> like(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return interactService.like(id, userId);
    }

    /** 取消点赞 */
    @DeleteMapping("/{id}/like")
    public Result<Void> unlike(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return interactService.unlike(id, userId);
    }

    /** 收藏 */
    @PostMapping("/{id}/favorite")
    public Result<Void> favorite(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return interactService.favorite(id, userId);
    }

    /** 取消收藏 */
    @DeleteMapping("/{id}/favorite")
    public Result<Void> unfavorite(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return interactService.unfavorite(id, userId);
    }

    /** 分享 */
    @PostMapping("/{id}/share")
    public Result<Void> share(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return interactService.share(id, userId);
    }

    /** 取消分享 */
    @DeleteMapping("/{id}/share")
    public Result<Void> unshare(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(401, "请先登录");
        }
        return interactService.unshare(id, userId);
    }
}
