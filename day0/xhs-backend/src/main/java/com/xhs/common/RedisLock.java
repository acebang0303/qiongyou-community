package com.xhs.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ★ P3-2：Redis 分布式锁（释放时校验锁归属）
 *
 * 获取：`SET key &lt;唯一token&gt; NX EX ttl` —— 一条命令同时保证互斥与防死锁
 * 释放：Lua 先比对 value 再删除 —— **只删自己加的锁**
 *
 * 为什么必须比对：锁有 TTL，业务若执行超过 TTL，锁会自动过期并被别人抢到；
 * 此时若直接 `DEL`，会把**别人**的锁删掉，互斥当场失效（两个线程同时回源重建）。
 *
 * ⚠️ token 与 Lua 都必须走 `StringRedisTemplate`（裸字符串）：
 * 用 JSON 序列化的 `RedisTemplate` 会把 token 写成带引号的 `"uuid"`，
 * 与 ARGV 里的 `uuid` 比对永远不相等 → 锁永远删不掉，只能等 TTL 过期。
 */
@Component
public class RedisLock {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 抢锁成功返回唯一 token（释放时要带回来）；失败返回 null */
    public String tryLock(String key, long ttlSeconds) {
        String token = UUID.randomUUID().toString();
        Boolean ok = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, token, ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    /** 释放锁：token 不匹配（说明锁已过期且被他人持有）则什么都不做 */
    public void unlock(String key, String token) {
        if (token == null) {
            return;
        }
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
    }
}
