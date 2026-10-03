package com.influencermatch.backend.security;

import static org.assertj.core.api.Assertions.*;

import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtAuthVersionTest {
  private final String secret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

  @Test
  void legacyVersionZeroIsAcceptedOnlyUntilRevoked() {
    var provider = new JwtTokenProvider();
    ReflectionTestUtils.setField(provider, "secretKey", secret);
    var user =
        User.builder()
            .id(UUID.randomUUID())
            .email("legacy@example.test")
            .role(Role.BRAND)
            .status(UserStatus.ACTIVE)
            .build();
    String legacy =
        Jwts.builder()
            .subject(user.getId().toString())
            .claim("userId", user.getId().toString())
            .claim("email", user.getEmail())
            .claim("role", "BRAND")
            .claim("type", "access")
            .expiration(new Date(System.currentTimeMillis() + 60000))
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret)))
            .compact();
    assertThat(provider.isTokenValid(legacy, user)).isTrue();
    user.setAuthVersion(1);
    assertThat(provider.isTokenValid(legacy, user)).isFalse();
    user.setAuthVersion(0);
    user.setRole(Role.DATA_MANAGER);
    assertThat(provider.isTokenValid(legacy, user)).isFalse();
  }
}
