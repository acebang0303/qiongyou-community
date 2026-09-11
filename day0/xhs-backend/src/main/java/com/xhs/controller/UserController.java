package com.xhs.controller;

import com.xhs.common.Result;
import com.xhs.entity.User;
import com.xhs.service.InteractService;
import com.xhs.service.NoteService;
import com.xhs.service.UserService;
import com.xhs.vo.NoteVO;
import com.xhs.vo.SimpleUserVO;
import com.xhs.vo.UserVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户接口：登录 / 个人主页 / 关注关系
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;
    @Autowired
    private NoteService noteService;
    @Autowired
    private InteractService interactService;

    /** 登录 */
    @PostMapping("/login")
    public Result<User> login(@RequestBody User loginForm) {
        return userService.login(loginForm.getUsername(), loginForm.getPassword());
    }

    /** 用户主页信息 */
    @GetMapping("/{id}")
    public Result<UserVO> userInfo(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long viewerId) {
        UserVO vo = userService.userInfo(id, viewerId);
        if (vo == null) {
            return Result.fail(404, "用户不存在");
        }
        return Result.ok(vo);
    }

    /** 用户发布的笔记 */
    @GetMapping("/{id}/notes")
    public Result<List<NoteVO>> userNotes(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long viewerId) {
        return Result.ok(noteService.notesByUser(id, viewerId));
    }

    /** 关注列表 */
    @GetMapping("/{id}/follows")
    public Result<List<SimpleUserVO>> follows(@PathVariable Long id) {
        return Result.ok(userService.follows(id));
    }

    /** 粉丝列表 */
    @GetMapping("/{id}/fans")
    public Result<List<SimpleUserVO>> fans(@PathVariable Long id) {
        return Result.ok(userService.fans(id));
    }

    /** 关注用户 */
    @PostMapping("/{id}/follow")
    public Result<Void> follow(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long viewerId) {
        if (viewerId == null) {
            return Result.fail(401, "请先登录");
        }
        return interactService.follow(viewerId, id);
    }

    /** 取消关注 */
    @DeleteMapping("/{id}/follow")
    public Result<Void> unfollow(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) Long viewerId) {
        if (viewerId == null) {
            return Result.fail(401, "请先登录");
        }
        return interactService.unfollow(viewerId, id);
    }
}
