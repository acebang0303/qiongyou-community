package com.qiongyou.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiongyou.common.JwtUtil;
import com.qiongyou.common.RedisKeys;
import com.qiongyou.common.RedisLock;
import com.qiongyou.common.Result;
import com.qiongyou.entity.Follow;
import com.qiongyou.entity.Note;
import com.qiongyou.entity.User;
import com.qiongyou.mapper.FollowMapper;
import com.qiongyou.mapper.NoteMapper;
import com.qiongyou.mapper.UserMapper;
import com.qiongyou.vo.LoginVO;
import com.qiongyou.vo.SimpleUserVO;
import com.qiongyou.vo.UserVO;
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
    /** ★ P3-5：没抢到锁时的轮询次数与间隔（合计约 100ms） */
    private static final int LOCK_WAIT_RETRIES = 5;
    private static final long LOCK_WAIT_INTERVAL_MS = 20;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private FollowMapper followMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private RedisLock redisLock;
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
     * ★ P3-2：锁值改为唯一 token，释放走 Lua 比对归属（避免误删别人的锁）
     * ★ P3-5：没抢到锁改为短暂轮询（原实现是递归重入 userInfo，存在递归风险）
     */
    private UserVO loadUserWithLock(Long userId, Long viewerId) {
        String key = RedisKeys.user(userId);
        String lockKey = RedisKeys.userLock(userId);
        // 【防击穿】抢锁：只有抢到的线程回源重建，其余线程等待后重读缓存
        String token = redisLock.tryLock(lockKey, LOCK_TTL_SECONDS);

        if (token == null) {
            return waitForUserCacheOrLoad(userId, key);
        }

        try {
            // 双重检查：可能在等锁期间缓存已被别人重建，避免重复回源
            UserVO vo = (UserVO) redisTemplate.opsForValue().get(key);
            if (vo != null) {
                return vo;
            }
            vo = loadUserFromDb(userId);
            if (vo == null) {
                // 【防穿透】查库为空也缓存一个空对象（短 TTL），读取侧用 getId()==null 识别
                redisTemplate.opsForValue()
                        .set(key, new UserVO(), NULL_TTL_SECONDS, TimeUnit.SECONDS);
                return null;
            }
            // 【防雪崩】TTL = 基础值 + 随机抖动，把大量 Key 的过期时刻打散
            long ttl = BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(JITTER_SECONDS);
            redisTemplate.opsForValue().set(key, vo, ttl, TimeUnit.SECONDS);
            return vo;
        } finally {
            redisLock.unlock(lockKey, token);
        }
    }

    /** 没抢到锁：短暂轮询等持锁者把缓存建好；始终没有则兜底查库 */
    private UserVO waitForUserCacheOrLoad(Long userId, String key) {
        for (int i = 0; i < LOCK_WAIT_RETRIES; i++) {
            try {
                Thread.sleep(LOCK_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            UserVO cached = (UserVO) redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return cached;
            }
        }
        return loadUserFromDb(userId);
    }

    /** 从 DB 组装 UserVO（不含任何缓存逻辑，供重建与兜底共用） */
    private UserVO loadUserFromDb(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        vo.setNoteCount(noteMapper.selectCount(
                new LambdaQueryWrapper<Note>().eq(Note::getUserId, userId)).intValue());
        vo.setFollowCount(followMapper.selectCount(
                new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId)).intValue());
        vo.setFansCount(followMapper.selectCount(
                new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, userId)).intValue());
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
