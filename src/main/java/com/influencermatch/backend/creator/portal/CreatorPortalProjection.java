package com.influencermatch.backend.creator.portal;

import static com.influencermatch.backend.creator.portal.PortalDtos.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class CreatorPortalProjection {
  final JdbcTemplate db;
  final ObjectMapper json;
  final Clock clock;

  record Evidence(
      UUID creator,
      UUID owner,
      String method,
      String status,
      LocalDateTime at,
      String source,
      boolean tokenStored,
      LocalDateTime tokenExpires) {}

  static String marks(Collection<?> ids) {
    return String.join(",", Collections.nCopies(ids.size(), "?"));
  }

  static LocalDateTime time(Object value) {
    return value == null
        ? null
        : value instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) value;
  }

  static boolean active(Map<String, Object> c) {
    return "AUTHORIZED".equals(c.get("source_type"))
        && c.get("master_id") != null
        && "ACTIVE".equals(c.get("master_status"))
        && "CREATOR".equals(c.get("owner_role"))
        && "ACTIVE".equals(c.get("owner_status"))
        && Boolean.TRUE.equals(c.get("email_verified"));
  }

  static Comparator<Evidence> latestEvidence() {
    return Comparator.comparing(Evidence::at)
        .thenComparing(e -> !"ACTIVE".equals(e.status()))
        .thenComparing(Evidence::source)
        .thenComparing(e -> Objects.toString(e.method(), ""));
  }

  static Verification verification(Map<String, Object> c, List<Evidence> evidence) {
    if (!active(c)) return new Verification("UNVERIFIED", null, null, "NONE", null);
    var current =
        evidence.stream()
            .filter(e -> Objects.equals(e.owner, c.get("owner_id")) && e.at != null)
            .toList();
    var oauth = new HashMap<String, Evidence>();
    var effective = new ArrayList<Evidence>();
    for (var e : current) {
      if ("CREATOR_CLAIM".equals(e.source)) effective.add(e);
      else
        oauth.merge(
            Objects.toString(e.method, ""),
            e,
            (a, b) -> latestEvidence().compare(a, b) >= 0 ? a : b);
    }
    effective.addAll(oauth.values());
    var candidate =
        effective.stream()
            .filter(e -> "ACTIVE".equals(e.status) && trusted(c, e))
            .max(latestEvidence());
    if (candidate.isEmpty()) candidate = effective.stream().max(latestEvidence());
    if (candidate.isEmpty())
      return new Verification("LEGACY_UNVERIFIED", null, null, "NONE", "LEGACY");
    var e = candidate.get();
    if (!"ACTIVE".equals(e.status))
      return new Verification("UNVERIFIED", e.method, e.at, e.source, null);
    if (e.method != null && e.method.contains("MOCK"))
      return new Verification("SIMULATED", e.method, e.at, e.source, "DEV");
    boolean trusted = trusted(c, e);
    return new Verification(
        trusted ? "VERIFIED" : "LEGACY_UNVERIFIED",
        e.method,
        e.at,
        e.source,
        trusted
            ? ("TIKTOK_OAUTH_SANDBOX".equals(e.method)
                ? "SANDBOX"
                : "MANUAL_REVIEW".equals(e.method) ? "MANUAL" : "OFFICIAL_API")
            : "LEGACY");
  }

  static boolean trusted(Map<String, Object> c, Evidence e) {
    return ("CREATOR_CLAIM".equals(e.source) && "MANUAL_REVIEW".equals(e.method))
        || ("META_OAUTH".equals(e.method)
            && Set.of("FACEBOOK", "INSTAGRAM").contains(c.get("platform")))
        || ("INSTAGRAM_OAUTH".equals(e.method) && "INSTAGRAM".equals(c.get("platform")))
        || ("TIKTOK_OAUTH_SANDBOX".equals(e.method) && "TIKTOK".equals(c.get("platform")));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<UUID, Projection> project(Collection<UUID> input, boolean full) {
    var ids = new LinkedHashSet<>(input);
    if (ids.isEmpty()) return Map.of();
    var channels =
        db.queryForList(
            "select c.*,m.user_id owner_id,m.status master_status,u.role owner_role,u.status"
                + " owner_status,u.email_verified from creator c left join creator_master m on"
                + " m.id=c.master_id left join \"user\" u on u.id=m.user_id where c.id in ("
                + marks(ids)
                + ")",
            ids.toArray());
    var evidence = new HashMap<UUID, List<Evidence>>();
    for (var r :
        db.queryForList(
            "select"
                + " creator_id,verified_by_user_id,verification_method,status,verified_at,(access_token_encrypted"
                + " is not null) token_stored,token_expires_at from creator_account_verification"
                + " where creator_id in ("
                + marks(ids)
                + ") order by verified_at,id",
            ids.toArray())) {
      UUID id = (UUID) r.get("creator_id");
      evidence
          .computeIfAbsent(id, k -> new ArrayList<>())
          .add(
              new Evidence(
                  id,
                  (UUID) r.get("verified_by_user_id"),
                  (String) r.get("verification_method"),
                  (String) r.get("status"),
                  time(r.get("verified_at")),
                  "ACCOUNT_VERIFICATION",
                  Boolean.TRUE.equals(r.get("token_stored")),
                  time(r.get("token_expires_at"))));
    }
    for (var r :
        db.queryForList(
            "select creator_id,claimant_id,status,reviewed_at,revoked_at from creator_claim where"
                + " reviewer_id is not null and reviewed_at is not null and status in"
                + " ('APPROVED','REVOKED') and creator_id in ("
                + marks(ids)
                + ") order by reviewed_at,id",
            ids.toArray())) {
      UUID id = (UUID) r.get("creator_id");
      boolean revoked = "REVOKED".equals(r.get("status"));
      evidence
          .computeIfAbsent(id, k -> new ArrayList<>())
          .add(
              new Evidence(
                  id,
                  (UUID) r.get("claimant_id"),
                  "MANUAL_REVIEW",
                  revoked ? "REVOKED" : "ACTIVE",
                  time(revoked ? r.get("revoked_at") : r.get("reviewed_at")),
                  "CREATOR_CLAIM",
                  false,
                  null));
    }
    var masters = new LinkedHashSet<UUID>();
    channels.forEach(
        c -> {
          if (c.get("master_id") != null) masters.add((UUID) c.get("master_id"));
        });
    var profiles = new HashMap<UUID, Map<String, Object>>();
    if (!masters.isEmpty())
      for (var r :
          db.queryForList(
              "select * from creator_portal_profile where master_id in (" + marks(masters) + ")",
              masters.toArray())) profiles.put((UUID) r.get("master_id"), r);
    var portfolio = new HashMap<UUID, List<Portfolio>>();
    var profileIds = profiles.values().stream().map(r -> r.get("id")).toList();
    if (full && !profileIds.isEmpty())
      for (var r :
          db.queryForList(
              "select * from creator_portfolio_item where profile_id in ("
                  + marks(profileIds)
                  + ") order by position,id",
              profileIds.toArray()))
        portfolio
            .computeIfAbsent((UUID) r.get("profile_id"), k -> new ArrayList<>())
            .add(
                new Portfolio(
                    (UUID) r.get("id"),
                    (String) r.get("title"),
                    (String) r.get("url"),
                    (String) r.get("description")));
    var out = new HashMap<UUID, Projection>();
    for (var c : channels) {
      UUID id = (UUID) c.get("id"), master = (UUID) c.get("master_id");
      var v = verification(c, evidence.getOrDefault(id, List.of()));
      DeclaredProfile declared = null;
      var row = profiles.get(master);
      if (v.verified() && row != null) {
        try {
          var d = json.readTree(row.get("data").toString());
          var sharing = d.path("sharing");
          declared =
              new DeclaredProfile(
                  "CREATOR_DECLARED",
                  sharing.path("introduction").asBoolean() ? nullable(d.get("introduction")) : null,
                  sharing.path("serviceAreas").asBoolean() ? list(d.path("serviceAreas")) : null,
                  sharing.path("acceptedNiches").asBoolean()
                      ? list(d.path("acceptedNiches"))
                      : null,
                  full && sharing.path("portfolio").asBoolean()
                      ? portfolio.getOrDefault((UUID) row.get("id"), List.of())
                      : null,
                  sharing.path("availability").asBoolean()
                      ? json.treeToValue(d.path("availability"), Availability.class)
                      : null);
        } catch (Exception e) {
          throw new IllegalStateException("Invalid stored declared profile");
        }
      }
      var latest =
          evidence.getOrDefault(id, List.of()).stream()
              .filter(e -> Objects.equals(e.owner, c.get("owner_id")) && e.at != null)
              .max(latestEvidence());
      String token = "NOT_STORED";
      if (latest.isPresent() && latest.get().tokenStored) {
        var e = latest.get();
        token =
            !"ACTIVE".equals(e.status)
                ? e.status
                : e.tokenExpires != null && e.tokenExpires.isBefore(LocalDateTime.now(clock))
                    ? "EXPIRED"
                    : "ACTIVE";
      }
      out.put(id, new Projection(master, v, declared, token));
    }
    return out;
  }

  static String nullable(JsonNode n) {
    return n == null || n.isNull() ? null : n.asText();
  }

  static List<String> list(JsonNode n) {
    var out = new ArrayList<String>();
    n.forEach(v -> out.add(v.asText()));
    return out.isEmpty() ? null : out;
  }

  public boolean canViewContacts() {
    var actor = Permissions.actor();
    if (actor.getRole() != com.influencermatch.backend.user.enums.Role.BRAND) return false;
    try {
      Permissions.require(Permission.VIEW_CREATOR_CONTACT);
    } catch (ForbiddenException ex) {
      return false;
    }
    return db.queryForObject(
            "select count(*) from \"user\" u where id=? and role='BRAND' and status='ACTIVE' and"
                + " email_verified and auth_version=? and not exists(select 1 from"
                + " role_permission_override where role='BRAND' and"
                + " permission='VIEW_CREATOR_CONTACT' and not enabled)",
            Long.class,
            actor.getId(),
            actor.getAuthVersion())
        == 1;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public SharedContacts contacts(UUID creator, boolean requirePermission) {
    boolean allowed = canViewContacts();
    if (requirePermission && !allowed)
      throw new ForbiddenException("Brand contact permission required");
    var rows = db.queryForList("select id,master_id from creator where id=?", creator);
    if (rows.isEmpty()) throw new NotFoundException("Creator not found");
    var c = rows.getFirst();
    UUID master = (UUID) c.get("master_id");
    var hidden = new SharedContact(null, "NOT_SHARED", null, false);
    if (!allowed) return new SharedContacts(creator, hidden, hidden, hidden);
    if (master == null) {
      var metric =
          db.queryForList(
              "select contact,content_summary from public_creator_metric where creator_id=? order"
                  + " by collected_at desc,id desc limit 1",
              creator);
      String email = null;
      if (!metric.isEmpty())
        for (String field : List.of("contact", "content_summary")) {
          var matcher =
              java.util.regex.Pattern.compile(
                      "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
                  .matcher(Objects.toString(metric.getFirst().get(field), ""));
          if (matcher.find()) {
            email = matcher.group();
            break;
          }
        }
      return new SharedContacts(
          creator,
          email == null ? hidden : new SharedContact(email, "AVAILABLE", "PUBLIC_CRAWL", false),
          hidden,
          hidden);
    }
    var p = project(List.of(creator), false).get(creator);
    if (p == null || !p.verification().verified())
      return new SharedContacts(creator, hidden, hidden, hidden);
    var data = db.queryForList("select data from creator_portal_profile where master_id=?", master);
    if (data.isEmpty()) return new SharedContacts(creator, hidden, hidden, hidden);
    boolean partner =
        db.queryForObject(
                """
select count(*) from cooperation_deal d join campaign ca on ca.id=d.campaign_id join brand_profile b on b.id=ca.brand_profile_id join collaboration l on l.id=d.collaboration_id
where d.master_id=? and b.user_id=? and d.status='CONFIRMED' and l.status<>'CANCELLED'
and exists(select 1 from cooperation_member mm where mm.deal_id=d.id)
and not exists(select 1 from cooperation_member mm join creator c on c.id=mm.creator_id where mm.deal_id=d.id and c.master_id is distinct from d.master_id)
""",
                Long.class,
                master,
                Permissions.actor().getId())
            > 0;
    try {
      var node = json.readTree(data.getFirst().get("data").toString()).path("contacts");
      return new SharedContacts(
          creator,
          shared(node.path("bookingEmail"), partner),
          shared(node.path("zalo"), partner),
          shared(node.path("managerContact"), partner));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored contacts");
    }
  }

  static SharedContact shared(JsonNode c, boolean partner) {
    String visibility = c.path("visibility").asText();
    String value = nullable(c.get("value"));
    boolean visible =
        "BRANDS".equals(visibility) || partner && "CONFIRMED_PARTNERS".equals(visibility);
    return visible && value != null
        ? new SharedContact(value, "AVAILABLE", "CREATOR_DECLARED", false)
        : new SharedContact(null, "NOT_SHARED", null, false);
  }
}
