package com.influencermatch.backend.support;

import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class TestActors {
  private TestActors() {}
  public static User user(Role role) {
    return User.builder().id(UUID.randomUUID()).email(UUID.randomUUID()+"@unit.test")
        .fullName("Unit Test").role(role).status(UserStatus.ACTIVE).build();
  }
  public static Authentication authenticate(User user) {
    var auth=new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities());
    SecurityContextHolder.getContext().setAuthentication(auth);return auth;
  }
}
