package com.influencermatch.backend.user.service;

import com.influencermatch.backend.exception.ValidationException;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;

public final class UserAccountSearch {
  private UserAccountSearch() {}

  public static PageRequest page(int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw new ValidationException("page must be nonnegative; size must be 1-100");
    return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
  }

  public static String query(String q) {
    if (q != null && q.length() > 200)
      throw new ValidationException("q may contain at most 200 characters");
    return q == null ? null : q.trim();
  }

  public static Specification<User> filter(Role role, UserStatus status, String q) {
    String search = query(q);
    return (r, query, cb) -> {
      var conditions = new ArrayList<jakarta.persistence.criteria.Predicate>();
      if (role != null) conditions.add(cb.equal(r.get("role"), role));
      if (status != null) conditions.add(cb.equal(r.get("status"), status));
      if (search != null && !search.isEmpty()) {
        String pattern =
            "%"
                + search
                    .toLowerCase(Locale.ROOT)
                    .replace("!", "!!")
                    .replace("%", "!%")
                    .replace("_", "!_")
                + "%";
        var names = new ArrayList<jakarta.persistence.criteria.Predicate>();
        names.add(cb.like(cb.lower(r.get("email")), pattern, '!'));
        names.add(cb.like(cb.lower(cb.coalesce(r.get("fullName"), "")), pattern, '!'));
        try {
          UUID id = UUID.fromString(search);
          if (id.toString().equalsIgnoreCase(search)) names.add(cb.equal(r.get("id"), id));
        } catch (IllegalArgumentException ignored) {
        }
        conditions.add(cb.or(names.toArray(jakarta.persistence.criteria.Predicate[]::new)));
      }
      return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
    };
  }
}
