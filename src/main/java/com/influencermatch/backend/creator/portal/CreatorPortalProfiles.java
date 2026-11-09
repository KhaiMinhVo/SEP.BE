package com.influencermatch.backend.creator.portal;

import static com.influencermatch.backend.creator.portal.PortalDtos.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class CreatorPortalProfiles {
  final JdbcTemplate db;
  final ObjectMapper json;
  final AuditService audit;
  final Clock clock;
  final jakarta.validation.Validator validator;

  public static String tag(UUID user, long revision) {
    return "\"creator-profile:" + user + ":r" + revision + "\"";
  }

  UUID actor() {
    return Permissions.actor().getId();
  }

  void guard(Permission permission) {
    Permissions.require(permission);
    var u =
        db.queryForMap(
            "select role,status,email_verified,auth_version from \"user\" where id=?", actor());
    if (!"CREATOR".equals(u.get("role"))
        || !"ACTIVE".equals(u.get("status"))
        || !Boolean.TRUE.equals(u.get("email_verified"))
        || ((Number) u.get("auth_version")).longValue() != Permissions.actor().getAuthVersion())
      throw new ForbiddenException("Active verified Creator required");
    if (db.queryForObject(
            "select count(*) from role_permission_override where role='CREATOR' and permission=?"
                + " and not enabled",
            Long.class,
            permission.name())
        > 0) throw new ForbiddenException("Permission disabled");
  }

  Map<String, Object> master() {
    var rows = db.queryForList("select id,status from creator_master where user_id=?", actor());
    if (rows.isEmpty()) return null;
    var m = rows.getFirst();
    if (!"ACTIVE".equals(m.get("status")))
      throw new ForbiddenException("Creator Master is not active");
    return m;
  }

  Map<String, Object> row(UUID master) {
    return master == null
        ? null
        : db.queryForList("select * from creator_portal_profile where master_id=?", master).stream()
            .findFirst()
            .orElse(null);
  }

  public ObjectNode data(Map<String, Object> row) {
    if (row == null) return PortalData.empty(json);
    try {
      var data = (ObjectNode) json.readTree(row.get("data").toString());
      var p = json.createArrayNode();
      for (var item :
          db.queryForList(
              "select id,title,url,description from creator_portfolio_item where profile_id=? order"
                  + " by position",
              row.get("id")))
        p.add(
            json.valueToTree(
                new Portfolio(
                    (UUID) item.get("id"),
                    (String) item.get("title"),
                    (String) item.get("url"),
                    (String) item.get("description"))));
      data.set("portfolio", p);
      return data;
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored creator profile");
    }
  }

  Profile view(UUID master, Map<String, Object> row, ObjectNode data) {
    try {
      return new Profile(
          master,
          row != null,
          row == null ? 0 : ((Number) row.get("profile_revision")).longValue(),
          "CREATOR_DECLARED",
          json.treeToValue(data, Data.class));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored creator profile");
    }
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Profile get() {
    guard(Permission.VIEW_OWN_PROFILE);
    var m = master();
    UUID id = m == null ? null : (UUID) m.get("id");
    var row = row(id);
    return view(id, row, data(row));
  }

  @Transactional
  public Profile patch(JsonNode patch, String header) {
    guard(Permission.VIEW_OWN_PROFILE);
    guard(Permission.MANAGE_OWN_CREATOR_PROFILE);
    db.queryForObject("select id from \"user\" where id=? for update", UUID.class, actor());
    guard(Permission.VIEW_OWN_PROFILE);
    guard(Permission.MANAGE_OWN_CREATOR_PROFILE);
    var m = master();
    UUID master = m == null ? null : (UUID) m.get("id");
    if (master != null) {
      var lockedMaster =
          db.queryForMap("select user_id,status from creator_master where id=? for update", master);
      if (!actor().equals(lockedMaster.get("user_id"))
          || !"ACTIVE".equals(lockedMaster.get("status")))
        throw new ForbiddenException("Creator Master authorization changed");
      db.queryForList("select id from creator_portal_profile where master_id=? for update", master);
    }
    var row = row(master);
    long revision = row == null ? 0 : ((Number) row.get("profile_revision")).longValue();
    if (header == null || header.isBlank())
      throw new BusinessException(ErrorCode.PRECONDITION_REQUIRED, "If-Match is required");
    if (!header.matches("\"creator-profile:[0-9a-fA-F-]{36}:r[0-9]+\""))
      throw new ValidationException("Invalid If-Match ETag");
    if (!header.equals(tag(actor(), revision)))
      throw new ConflictException(
          ErrorCode.CREATOR_PROFILE_REVISION_CONFLICT, "Creator profile changed");
    var before = data(row);
    var after = PortalData.patch(json, before, patch);
    if (!validator
        .validate(
            new BookingAddress(
                CreatorPortalProjection.nullable(after.at("/contacts/bookingEmail/value"))))
        .isEmpty()) throw new ValidationException("Invalid booking email");
    if (before.equals(after)) return view(master, row, before);
    if (master == null) {
      master = UUID.randomUUID();
      String name = Objects.toString(Permissions.actor().getFullName(), "Creator");
      db.update(
          "insert into creator_master(id,user_id,canonical_name,status,created_at,updated_at)"
              + " values(?,?,?,'ACTIVE',now(),now())",
          master,
          actor(),
          name);
    }
    UUID id = row == null ? UUID.randomUUID() : (UUID) row.get("id");
    var stored = after.deepCopy();
    stored.remove("portfolio");
    if (row == null)
      db.update(
          "insert into"
              + " creator_portal_profile(id,master_id,profile_revision,data,created_at,updated_at)"
              + " values(?,?,1,?::jsonb,?,?)",
          id,
          master,
          stored.toString(),
          java.sql.Timestamp.from(clock.instant()),
          java.sql.Timestamp.from(clock.instant()));
    else
      db.update(
          "update creator_portal_profile set"
              + " profile_revision=profile_revision+1,data=?::jsonb,updated_at=? where id=?",
          stored.toString(),
          java.sql.Timestamp.from(clock.instant()),
          id);
    db.update("delete from creator_portfolio_item where profile_id=?", id);
    int position = 0;
    for (var p : after.path("portfolio"))
      db.update(
          "insert into creator_portfolio_item(id,profile_id,title,url,description,position)"
              + " values(?,?,?,?,?,?)",
          UUID.fromString(p.path("id").asText()),
          id,
          p.path("title").asText(),
          p.path("url").asText(),
          p.path("description").isNull() ? null : p.path("description").asText(),
          position++);
    var changed = new ArrayList<String>();
    after
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!Objects.equals(before.get(k), after.get(k))) changed.add(k);
            });
    audit.record(
        "UPDATE_CREATOR_PROFILE",
        "CreatorMaster",
        master,
        null,
        Map.of(
            "changedFields",
            changed,
            "profileRevision",
            revision + 1,
            "sharing",
            json.convertValue(after.path("sharing"), Map.class),
            "contactVisibility",
            Map.of(
                "bookingEmail",
                after.at("/contacts/bookingEmail/visibility").asText(),
                "zalo",
                after.at("/contacts/zalo/visibility").asText(),
                "managerContact",
                after.at("/contacts/managerContact/visibility").asText())),
        "Creator updated declared profile; values omitted from audit");
    return view(master, row(master), after);
  }
}
