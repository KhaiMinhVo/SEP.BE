package com.influencermatch.backend.security.role;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.*;
import com.influencermatch.backend.user.enums.Role;
import jakarta.validation.Validator;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.influencermatch.backend.security.role.RolePermissionDtos.*;

@Service @RequiredArgsConstructor @PreAuthorize("hasRole('ADMIN')")
public class RolePermissionService {
  private final RolePermissionConfigRepository configs;
  private final RolePermissionOverrideRepository overrides;
  private final AuditService audit;
  private final Validator validator;

  @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public List<RoleView> catalog() {
    requireAdmin();
    var versions = new EnumMap<Role, Long>(Role.class);
    configs.findAll().forEach(c -> versions.put(c.getRole(), c.getVersion()));
    var all = overrides.findAll();
    return Arrays.stream(Role.values()).map(r -> view(r, versions.getOrDefault(r, 0L), all)).toList();
  }

  @Transactional
  public RoleView update(Role role, Update request) {
    requireAdmin();
    if (role == null || role == Role.ADMIN || request == null || !validator.validate(request).isEmpty())
      throw invalid("Role or changes are not editable");
    var defaults = RolePermissions.forRole(role);
    var seen = EnumSet.noneOf(Permission.class);
    for (var change : request.changes()) {
      if (!seen.add(change.permission()) || !defaults.contains(change.permission())
          || !PermissionCatalog.implemented(change.permission()))
        throw invalid("Duplicate, unsupported or out-of-role permission");
    }
    var config = configs.lockByRole(role).orElseThrow(() ->
        new IllegalStateException("Missing role permission configuration: " + role));
    if (config.getVersion() != request.version())
      throw new BusinessException(ErrorCode.OPTIMISTIC_CONFLICT, "Role permissions changed; reload configuration");
    var rows = new ArrayList<>(overrides.findByRole(role));
    var before = view(role, config.getVersion(), rows);
    boolean changed = false;
    for (var change : request.changes()) {
      var row = rows.stream().filter(o -> o.getPermission() == change.permission()).findFirst().orElse(null);
      boolean enabled = row == null || row.isEnabled();
      if (enabled == change.enabled()) continue;
      changed = true;
      if (row == null) {
        row = new RolePermissionOverride(); row.setId(UUID.randomUUID());
        row.setRole(role); row.setPermission(change.permission()); rows.add(row);
      }
      row.setEnabled(change.enabled()); overrides.save(row);
    }
    if (changed) {
      config.setVersion(config.getVersion() + 1);
      configs.save(config);
      var after = view(role, config.getVersion(), rows);
      audit.record("UPDATE_ROLE_PERMISSIONS", "ROLE_PERMISSION", config.getId(), before, after,
          "Updated permissions for " + role);
      return after;
    }
    return before;
  }

  private RoleView view(Role role, long version, List<RolePermissionOverride> rows) {
    var disabled = EnumSet.noneOf(Permission.class);
    if (role != Role.ADMIN) rows.stream().filter(r -> r.getRole() == role && !r.isEnabled())
        .forEach(r -> disabled.add(r.getPermission()));
    var defaults = RolePermissions.forRole(role);
    return new RoleView(role, version, Arrays.stream(Permission.values()).map(p -> {
      var entry = PermissionCatalog.entry(p);
      return new Capability(p, entry.name(), entry.module(), defaults.contains(p),
          defaults.contains(p) && !disabled.contains(p), PermissionCatalog.implemented(p),
          role != Role.ADMIN && defaults.contains(p) && PermissionCatalog.implemented(p));
    }).toList());
  }
  private void requireAdmin() {
    if (Permissions.actor().getRole() != Role.ADMIN) throw new ForbiddenException("Administrator required");
  }
  private BusinessException invalid(String message) {
    return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
  }
}
