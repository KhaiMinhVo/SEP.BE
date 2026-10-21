package com.influencermatch.backend.security.permission;

import com.influencermatch.backend.user.enums.Role;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "role_permission_config")
@Getter
@Setter
@NoArgsConstructor
public class RolePermissionConfig {
  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, unique = true, length = 32)
  private Role role;

  @Column(nullable = false)
  private long version;
}
