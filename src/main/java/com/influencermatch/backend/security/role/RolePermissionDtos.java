package com.influencermatch.backend.security.role;

import com.influencermatch.backend.security.Permission;
import com.influencermatch.backend.user.enums.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class RolePermissionDtos {
  private RolePermissionDtos() {}
  public record Change(@NotNull Permission permission, @NotNull Boolean enabled) {}
  public record Update(@NotNull @Min(0) Long version,
      @NotEmpty @Size(max=100) List<@NotNull @Valid Change> changes) {}
  public record Capability(Permission permission, String name, String module,
      boolean defaultEnabled, boolean enabled, boolean implemented, boolean editable) {}
  public record RoleView(Role role, long version, List<Capability> permissions) {}
}
