package com.influencermatch.backend.security;

import com.influencermatch.backend.user.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.util.Date;
import java.util.function.Function;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Handles JWT generation, validation, and claim extraction. Custom claims embedded in each token:
 * userId, email, role.
 */
@Slf4j
@Component
public class JwtTokenProvider {

  public static final String CLAIM_USER_ID = "userId";
  public static final String CLAIM_EMAIL = "email";
  public static final String CLAIM_ROLE = "role";
  public static final String CLAIM_TYPE = "type";

  @Value("${application.security.jwt.secret-key}")
  private String secretKey;

  @Value("${application.security.jwt.expiration}")
  private long jwtExpirationMs;

  public String generateToken(User user) {
    long now = System.currentTimeMillis();
    return Jwts.builder()
        .subject(user.getId().toString())
        .id(java.util.UUID.randomUUID().toString())
        .claim(CLAIM_USER_ID, user.getId().toString())
        .claim(CLAIM_EMAIL, user.getEmail())
        .claim(CLAIM_ROLE, user.getRole().name())
        .claim(CLAIM_TYPE, "access")
        .claim("authVersion", user.getAuthVersion())
        .issuedAt(new Date(now))
        .expiration(new Date(now + jwtExpirationMs))
        .signWith(getSigningKey(), Jwts.SIG.HS256)
        .compact();
  }

  public boolean isTokenValid(String token, UserDetails userDetails) {
    final String username = extractUsername(token);
    Claims claims = extractAllClaims(token);
    if (!(userDetails instanceof User user)) return false;
    Object rawVersion = claims.get("authVersion");
    long version = rawVersion == null ? 0 : ((Number) rawVersion).longValue();
    return username != null
        && username.equals(user.getEmail())
        && !isTokenExpired(token)
        && "access".equals(claims.get(CLAIM_TYPE, String.class))
        && user.getId().toString().equals(claims.get(CLAIM_USER_ID, String.class))
        && user.getRole().name().equals(claims.get(CLAIM_ROLE, String.class))
        && version == user.getAuthVersion();
  }

  public boolean validateToken(String token) {
    try {
      Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
      return true;
    } catch (SignatureException ex) {
      log.warn("Invalid JWT signature: {}", ex.getMessage());
    } catch (MalformedJwtException ex) {
      log.warn("Malformed JWT token: {}", ex.getMessage());
    } catch (ExpiredJwtException ex) {
      log.warn("Expired JWT token: {}", ex.getMessage());
    } catch (UnsupportedJwtException ex) {
      log.warn("Unsupported JWT token: {}", ex.getMessage());
    } catch (IllegalArgumentException ex) {
      log.warn("JWT claims empty: {}", ex.getMessage());
    }
    return false;
  }

  public String extractUsername(String token) {
    return extractClaim(token, claims -> claims.get(CLAIM_EMAIL, String.class));
  }

  public String extractUserId(String token) {
    return extractClaim(token, claims -> claims.get(CLAIM_USER_ID, String.class));
  }

  public String extractRole(String token) {
    return extractClaim(token, claims -> claims.get(CLAIM_ROLE, String.class));
  }

  public Date extractExpiration(String token) {
    return extractClaim(token, Claims::getExpiration);
  }

  /** Returns remaining token lifetime in seconds (0 if already expired). */
  public long extractExpiresInSeconds(String token) {
    long remaining = extractExpiration(token).getTime() - System.currentTimeMillis();
    return Math.max(0L, remaining / 1000L);
  }

  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    return claimsResolver.apply(extractAllClaims(token));
  }

  private Claims extractAllClaims(String token) {
    return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
  }

  private boolean isTokenExpired(String token) {
    return extractExpiration(token).before(new Date());
  }

  private SecretKey getSigningKey() {
    byte[] keyBytes = Decoders.BASE64.decode(secretKey);
    return Keys.hmacShaKeyFor(keyBytes);
  }
}
