package com.influencermatch.backend.security.permission;

import com.influencermatch.backend.user.enums.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RolePermissionOverrideRepository
    extends JpaRepository<RolePermissionOverride, UUID> {
  List<RolePermissionOverride> findByRole(Role role);
}
