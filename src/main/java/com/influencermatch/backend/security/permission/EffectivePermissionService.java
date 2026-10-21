package com.influencermatch.backend.security.permission;

import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;

/** Immutable permission snapshot per role per request; never cached between requests. */
@Service
@RequiredArgsConstructor
public class EffectivePermissionService {
  private static final String CACHE = EffectivePermissionService.class.getName() + ".";
  private final RolePermissionOverrideRepository overrides;

  public Set<Permission> resolve(Role role) {
    return resolve(role, RequestContextHolder.getRequestAttributes());
  }

  private Set<Permission> resolve(Role role, RequestAttributes request) {
    if (request != null) {
      @SuppressWarnings("unchecked")
      var cached = (Set<Permission>) request.getAttribute(CACHE + role, RequestAttributes.SCOPE_REQUEST);
      if (cached != null) return cached;
    }
    EnumSet<Permission> effective = EnumSet.noneOf(Permission.class);
    effective.addAll(RolePermissions.forRole(role));
    if (role != Role.ADMIN) {
      overrides.findByRole(role).stream().filter(o -> !o.isEnabled())
          .forEach(o -> effective.remove(o.getPermission()));
    }
    var snapshot = Collections.unmodifiableSet(effective);
    if (request != null) request.setAttribute(CACHE + role, snapshot, RequestAttributes.SCOPE_REQUEST);
    return snapshot;
  }

  public List<String> names(Role role) {
    return resolve(role).stream().map(Enum::name).sorted().toList();
  }

  public Collection<GrantedAuthority> authorities(Role role) {
    return authorities(role, resolve(role));
  }

  // Security filters can run before RequestContextFilter/DispatcherServlet binds the request.
  public Collection<GrantedAuthority> authorities(Role role, jakarta.servlet.http.HttpServletRequest request) {
    return authorities(role, resolve(role, new ServletRequestAttributes(request)));
  }

  private Collection<GrantedAuthority> authorities(Role role, Set<Permission> snapshot) {
    List<GrantedAuthority> result = new ArrayList<>();
    result.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
    snapshot.stream().map(Enum::name).sorted().forEach(p -> result.add(new SimpleGrantedAuthority(p)));
    return List.copyOf(result);
  }
}
