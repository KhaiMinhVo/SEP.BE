package com.influencermatch.backend.security.permission.repository;

import com.influencermatch.backend.security.permission.model.RolePermissionConfig;
import com.influencermatch.backend.user.enums.Role;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RolePermissionConfigRepository extends JpaRepository<RolePermissionConfig, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from RolePermissionConfig c where c.role = :role")
  Optional<RolePermissionConfig> lockByRole(@Param("role") Role role);
}
