package com.xhs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xhs.common.JwtUtil;
import com.xhs.common.RedisKeys;
import com.xhs.common.Result;
import com.xhs.entity.Follow;
import com.xhs.entity.Note;
import com.xhs.entity.User;
import com.xhs.mapper.FollowMapper;
import com.xhs.mapper.NoteMapper;
import com.xhs.mapper.UserMapper;
import com.xhs.vo.LoginVO;
import com.xhs.vo.SimpleUserVO;
import com.xhs.vo.UserVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务：登录、个人主页、关注/粉丝
 */
@Service
public class UserService {

    /** 基础缓存时长（秒）：30分钟 */
    private static final long BASE_TTL_SECONDS = 1800;
    /** 随机抖动上限（秒）：防雪崩 */
    private static final long JITTER_SECONDS = 300;
    /** 空对象缓存时长（秒）：防穿透 */
    private static final long NULL_TTL_SECONDS = 60;
    /** 重建锁超时（秒）：防持锁线程崩溃导致死锁 */
    private static final long LOCK_TTL_SECONDS = 10;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 登录：BCrypt 密文比对通过后签发 JWT 返回
     */
    public Result<LoginVO> login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return Result.fail(400, "用户名和密码不能为空");
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        // ★ P0-3：BCrypt 比对（user 为 null 时短路，不触发空指针）
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            return Result.fail(401, "用户名或密码错误");
        }
        // ★ P0-2：签发 token，后续请求以 Authorization: Bearer 携带
        String token = jwtUtil.generate(user.getId());
        user.setPassword(null);
        return Result.ok(new LoginVO(token, user));
    }

    /**
     * ★ Day5 改造：用户主页缓存 → 未命中走分布式锁互斥重建
     */
    public UserVO userInfo(Long userId, Long viewerId) {
        String key = RedisKeys.user(userId);
        UserVO vo = (UserVO) redisTemplate.opsForValue().get(key);
        if (vo == null) {
            vo = loadUserWithLock(userId, viewerId);
        }
        // 空对象缓存（id为null）表示用户不存在
        if (vo == null || vo.getId() == null) {
            return null;
        }
        // followed 与访问者相关，不能进缓存，每次请求单独判定
        if (viewerId != null && !viewerId.equals(userId)) {
            Long followed = followMapper.selectCount(
                    new LambdaQueryWrapper<Follow>()
                            .eq(Follow::getUserId, viewerId)
                            .eq(Follow::getFollowUserId, userId));
            vo.setFollowed(followed > 0);
        }
        return vo;
    }

    /**
     * ★ 分布式锁互斥重建
     * 抢到锁 → 双重检查 → 查库 → 回填（空对象/随机TTL）→ 释放锁
     * 没抢到 → 短暂等待后重读缓存（重新走一遍 userInfo 的缓存检查）
     */
    private UserVO loadUserWithLock(Long userId, Long viewerId) {
        String key = RedisKeys.user(userId);
        String lockKey = RedisKeys.userLock(userId);
        // 【防击穿】抢锁：只有抢到的线程回源重建，其余线程等待后重读缓存
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(locked)) {
            // 没抢到锁：等一下再重读缓存，此时大概率已被别人重建好
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return userInfo(userId, viewerId);
        }

        try {
            // 双重检查：可能在等锁期间缓存已被别人重建，避免重复回源
            UserVO vo = (UserVO) redisTemplate.opsForValue().get(key);
            if (vo != null) {
                return vo;
            }
            User user = userMapper.selectById(userId);
            if (user == null) {
                // 【防穿透】查库为空也缓存一个空对象（短 TTL），读取侧用 getId()==null 识别
                redisTemplate.opsForValue()
                        .set(key, new UserVO(), NULL_TTL_SECONDS, TimeUnit.SECONDS);
                return null;
            }
            vo = new UserVO();
            BeanUtils.copyProperties(user, vo);
            vo.setNoteCount(noteMapper.selectCount(
                    new LambdaQueryWrapper<Note>().eq(Note::getUserId, userId)).intValue());
            vo.setFollowCount(followMapper.selectCount(
                    new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId)).intValue());
            vo.setFansCount(followMapper.selectCount(
                    new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, userId)).intValue());
            // 【防雪崩】TTL = 基础值 + 随机抖动，把大量 Key 的过期时刻打散
            long ttl = BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(JITTER_SECONDS);
            redisTemplate.opsForValue().set(key, vo, ttl, TimeUnit.SECONDS);
            return vo;
        } finally {
            // 简化版释放：生产环境建议用 Lua "比较值再删除" 防止误删别人的锁
            redisTemplate.delete(lockKey);
        }
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
