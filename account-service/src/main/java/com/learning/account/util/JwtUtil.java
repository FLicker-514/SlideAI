package com.learning.account.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT工具类
 */
@Component
public class JwtUtil {
    
    @Value("${jwt.secret}")
    private String secret;
    
    @Value("${jwt.expiration}")
    private Long expiration;

    /**
     * 生成 Token
     * @param userId 用户ID
     * @param username 用户名
     * @param email 邮箱
     * @param isAdmin 是否管理员 (新增参数)
     * @return JWT字符串
     */
    public String generateToken(String userId, String username, String email, boolean isAdmin) {
        // 1. 准备 Claims
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);   // 冗余一份在 claims 里方便取
        claims.put("username", username);
        claims.put("email", email);
        claims.put("isAdmin", isAdmin); // 补上这个字段，Gateway 要用

        // 2. 构建 JWT
        return Jwts.builder()
                .claims(claims)          // 放入自定义数据
                .subject(userId)         // 标准 Subject 字段
                .issuedAt(new Date())    // 签发时间
                .expiration(new Date(System.currentTimeMillis() + expiration)) // 过期时间
                .signWith(getSignKey(), Jwts.SIG.HS512) // 显式指定算法 HS512
                .compact();
    }

    /**
     * 获取密钥 (必须与 Gateway 逻辑完全一致)
     */
    private SecretKey getSignKey() {
        // ⚠️ 必须使用 BASE64 解码，因为 Gateway 用了 BASE64 解码
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }
    
    /**
     * 从Token中获取用户ID
     */
    public String getUserIdFromToken(String token) {
        return getClaimsFromToken(token).getSubject();
    }
    
    /**
     * 从Token中获取Claims
     */
    private Claims getClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSignKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
    
    /**
     * 验证Token是否有效
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = getClaimsFromToken(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }
    
//    /**
//     * 获取签名密钥
//     * 确保密钥长度至少256位（32字节）
//     */
//    private SecretKey getSignKey() {
//        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
//
//        // 如果密钥长度不足32字节，使用SHA-256哈希扩展到32字节
//        if (keyBytes.length < 32) {
//            try {
//                java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
//                keyBytes = sha.digest(keyBytes);
//            } catch (Exception e) {
//                // 如果哈希失败，使用简单填充（不推荐但作为后备方案）
//                byte[] padded = new byte[32];
//                System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
//                // 如果原始密钥不够长，用密钥本身循环填充
//                for (int i = keyBytes.length; i < 32; i++) {
//                    padded[i] = keyBytes[i % keyBytes.length];
//                }
//                keyBytes = padded;
//            }
//        } else if (keyBytes.length > 64) {
//            // 如果密钥太长（超过512位），截取前64字节
//            byte[] trimmed = new byte[64];
//            System.arraycopy(keyBytes, 0, trimmed, 0, 64);
//            keyBytes = trimmed;
//        }
//
//        return Keys.hmacShaKeyFor(keyBytes);
//    }
}
