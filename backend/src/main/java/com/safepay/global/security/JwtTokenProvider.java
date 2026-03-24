package com.safepay.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiry}")
    private long accessTokenExpiry;

    @Value("${jwt.refresh-token-expiry}")
    private long refreshTokenExpiry;

    private SecretKey key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long memberId, String email, String role) {
        return createToken(memberId, email, role, accessTokenExpiry, null);
    }

    // Refresh Token 생성 — JTI(JWT ID) 포함
    public String createRefreshToken(Long memberId, String email, String role) {
        String jti = UUID.randomUUID().toString();
        return createToken(memberId, email, role, refreshTokenExpiry, jti);
    }

    private String createToken(Long memberId, String email, String role, long expiry, String jti) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiry);

        var builder = Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim("email", email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key);

        if (jti != null) {
            builder.id(jti);
        }

        return builder.compact();
    }

    public Long getMemberId(String token) {
        Claims claims = parseClaims(token);
        return Long.parseLong(claims.getSubject());
    }

    public String getEmail(String token) {
        Claims claims = parseClaims(token);
        return claims.get("email", String.class);
    }

    public String getRole(String token) {
        Claims claims = parseClaims(token);
        return claims.get("role", String.class);
    }

    // Refresh Token의 JTI(JWT ID)를 추출한다.
    public String getTokenId(String token) {
        Claims claims = parseClaims(token);
        return claims.getId();
    }

    public boolean validateToken(String token) {
        try {
            if (token == null || token.isBlank()) {
                return false;
            }
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired");
        } catch (JwtException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
        }
        return false;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
