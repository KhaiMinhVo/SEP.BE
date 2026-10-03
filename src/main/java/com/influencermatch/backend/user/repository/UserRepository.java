package com.influencermatch.backend.user.repository;

import com.influencermatch.backend.user.controller.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.service.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  long countByRoleAndStatus(Role role, com.influencermatch.backend.user.enums.UserStatus status);

  @org.springframework.data.jpa.repository.Query(
      value = "SELECT id FROM rbac_guard WHERE id=1 FOR UPDATE",
      nativeQuery = true)
  Integer lockRoleChanges();
}
