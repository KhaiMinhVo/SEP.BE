package com.influencermatch.backend.security.role;

import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionOverrideRepository extends JpaRepository<RolePermissionOverride, UUID> {
  List<RolePermissionOverride> findByRole(Role role);
}
