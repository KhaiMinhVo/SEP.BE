package com.influencermatch.backend.security.role;

import com.influencermatch.backend.user.enums.Role;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RolePermissionConfigRepository extends JpaRepository<RolePermissionConfig, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from RolePermissionConfig c where c.role = :role")
  Optional<RolePermissionConfig> lockByRole(@Param("role") Role role);
}
