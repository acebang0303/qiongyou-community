package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xhs.common.Result;
import com.xhs.entity.Follow;
import com.xhs.entity.Note;
import com.xhs.entity.User;
import com.xhs.mapper.FollowMapper;
import com.xhs.mapper.NoteMapper;
import com.xhs.mapper.UserMapper;
import com.xhs.vo.SimpleUserVO;
import com.xhs.vo.UserVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户服务：登录、个人主页、关注/粉丝
 */
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private FollowMapper followMapper;

    /**
     * 登录（基线版：明文密码比对，成功后返回用户信息）
     * 登录态由前端保存，后续请求通过 X-User-Id 请求头携带
     */
    public Result<User> login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return Result.fail(400, "用户名和密码不能为空");
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null || !user.getPassword().equals(password)) {
            return Result.fail(401, "用户名或密码错误");
        }
        user.setPassword(null);
        return Result.ok(user);
    }

    /** 用户主页信息：基本信息 + 笔记数/关注数/粉丝数 */
    public UserVO userInfo(Long userId, Long viewerId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);

        Long noteCount = noteMapper.selectCount(
                new LambdaQueryWrapper<Note>().eq(Note::getUserId, userId));
        Long followCount = followMapper.selectCount(
                new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId));
        Long fansCount = followMapper.selectCount(
                new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, userId));
        vo.setNoteCount(noteCount.intValue());
        vo.setFollowCount(followCount.intValue());
        vo.setFansCount(fansCount.intValue());

        if (viewerId != null && !viewerId.equals(userId)) {
            Long followed = followMapper.selectCount(
                    new LambdaQueryWrapper<Follow>()
                            .eq(Follow::getUserId, viewerId)
                            .eq(Follow::getFollowUserId, userId));
            vo.setFollowed(followed > 0);
        }
        return vo;
    }

    /** 关注列表 */
    public List<SimpleUserVO> follows(Long userId) {
        return followMapper.selectFollows(userId);
    }

    /** 粉丝列表 */
    public List<SimpleUserVO> fans(Long userId) {
        return followMapper.selectFans(userId);
    }
}
