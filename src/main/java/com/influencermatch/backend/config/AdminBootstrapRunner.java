package com.influencermatch.backend.config;

import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {
  private final BootstrapAdminProperties properties;
  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!properties.isEnabled()) return;
    properties.validateWhenEnabled();

    String email = properties.getEmail().trim().toLowerCase();
    User existing = users.findByEmail(email).orElse(null);
    if (existing == null) {
      users.save(
          User.builder()
              .email(email)
              .passwordHash(passwordEncoder.encode(properties.getPassword()))
              .fullName(properties.getFullName().trim())
              .role(Role.ADMIN)
              .status(UserStatus.ACTIVE)
              .build());
      log.info("Bootstrapped development ADMIN account email='{}'", email);
      return;
    }
    if (existing.getRole() != Role.ADMIN) {
      throw new IllegalStateException("Bootstrap email belongs to a non-ADMIN account");
    }
    if (existing.getStatus() != UserStatus.ACTIVE) {
      existing.setStatus(UserStatus.ACTIVE);
      existing.setAuthVersion(existing.getAuthVersion() + 1);
      users.save(existing);
      log.info("Reactivated development ADMIN account email='{}'", email);
    }
  }
}
