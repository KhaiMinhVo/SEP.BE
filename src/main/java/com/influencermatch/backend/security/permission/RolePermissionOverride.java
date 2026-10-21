package com.influencermatch.backend.security.permission;

import com.influencermatch.backend.user.enums.Role;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Entity
@Table(
    name = "role_permission_override",
    uniqueConstraints = @UniqueConstraint(columnNames = {"role", "permission"}))
@Getter
@Setter
@NoArgsConstructor
public class RolePermissionOverride {
  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private Role role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 80)
  private Permission permission;

  @Column(nullable = false)
  private boolean enabled;
}
