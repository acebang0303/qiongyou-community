package com.qiongyou.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/**
 * P0-2：JWT 签发与解析
 *
 * - 登录成功后签发：subject = userId，带过期时间，HS256 签名
 * - 每次请求解析并校验签名/有效期，失败抛 JwtException（由拦截器转 401）
 * 注意：key 由 secret 派生，HS256 要求 secret >= 32 字节
 */
@Component
public class JwtUtil {

    private final Key key;
    private final long expireMillis;

    public JwtUtil(@Value("${qiongyou.jwt.secret}") String secret,
                   @Value("${qiongyou.jwt.expire-minutes:120}") long expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireMinutes * 60_000L;
    }

    /** 签发 token */
    public String generate(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expireMillis))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** 解析并校验 token，返回 userId；签名错误/过期/格式非法均抛 JwtException */
    public Long parseUserId(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return Long.valueOf(claims.getSubject());
    }
}
