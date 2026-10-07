package com.qiongyou.controller;

import com.qiongyou.common.Result;
import com.qiongyou.common.UserContext;
import com.qiongyou.entity.User;
import com.qiongyou.service.InteractService;
import com.qiongyou.service.NoteService;
import com.qiongyou.service.UserService;
import com.qiongyou.vo.LoginVO;
import com.qiongyou.vo.NoteVO;
import com.qiongyou.vo.SimpleUserVO;
import com.qiongyou.vo.UserVO;
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
    public Result<LoginVO> login(@RequestBody User loginForm) {
        return userService.login(loginForm.getUsername(), loginForm.getPassword());
    }

    /** 用户主页信息 */
    @GetMapping("/{id}")
    public Result<UserVO> userInfo(@PathVariable Long id) {
        // ★ P0-2：登录身份从 UserContext 取（匿名时为 null）
        Long viewerId = UserContext.getUserId();
        UserVO vo = userService.userInfo(id, viewerId);
        if (vo == null) {
            return Result.fail(404, "用户不存在");
        }
        return Result.ok(vo);
    }

    /** 用户发布的笔记 */
    @GetMapping("/{id}/notes")
    public Result<List<NoteVO>> userNotes(@PathVariable Long id) {
        return Result.ok(noteService.notesByUser(id, UserContext.getUserId()));
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
    public Result<Void> follow(@PathVariable Long id) {
        Long viewerId = UserContext.getUserId();
        if (viewerId == null) {
            return Result.fail(401, "请先登录");
        }
        return interactService.follow(viewerId, id);
    }

    /** 取消关注 */
    @DeleteMapping("/{id}/follow")
    public Result<Void> unfollow(@PathVariable Long id) {
        Long viewerId = UserContext.getUserId();
        if (viewerId == null) {
            return Result.fail(401, "请先登录");
        }
        return interactService.unfollow(viewerId, id);
    }
}
