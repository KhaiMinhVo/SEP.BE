package com.influencermatch.backend.auth.service;

import com.fasterxml.jackson.annotation.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.auth.dto.*;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.ExternalIdentityRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GoogleBrandLoginService {
  private final GoogleIdTokenVerifier verifier;
  private final UserRepository users;
  private final ExternalIdentityRepository identities;
  private final AuthSessionIssuer issuer;
  private final AuthSessionStore authSessions;
  private final RegistrationSupport registration;
  private final PasswordEncoder encoder;
  private final AuditService audit;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public record Start(UUID sessionId, String clientId, String nonce, Instant expiresAt) {}

  public record Result(
      String status,
      UUID sessionId,
      String email,
      String fullName,
      Instant expiresAt,
      @JsonUnwrapped LoginResponse authentication) {}

  public record Complete(
      @NotNull UUID sessionId,
      @NotBlank @Size(max = 150) String fullName,
      @NotBlank @Size(max = 200) String businessName,
      @NotBlank @Size(max = 30) String phoneNumber,
      @Valid @NotNull PolicyAcceptance policyAcceptance) {
    @JsonAnySetter
    public void unknown(String key, Object value) {
      throw new ValidationException(
          "Google registration does not accept email, role or unknown fields");
    }
  }

  private record Challenge(
      UUID id,
      String nonce,
      String browser,
      Instant expires,
      boolean consumed,
      String purpose,
      String status,
      UUID user,
      UUID authSession,
      Long version,
      String subject,
      String email,
      String name) {}

  @Transactional
  public Start start(String browser) {
    return create(browser, "LOGIN", null, null);
  }

  private Start create(String browser, String purpose, User user, UUID sid) {
    String client = verifier.requireConfigured();
    UUID id = UUID.randomUUID();
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expires = clock.instant().plusSeconds(600);
    jdbc.update(
        "delete from google_login_session where expires_at<?",
        Timestamp.from(clock.instant().minusSeconds(86400)));
    jdbc.update(
        "update google_login_session set google_subject=null,google_email=null,google_name=null"
            + " where expires_at<=?",
        Timestamp.from(clock.instant()));
    jdbc.update(
        "insert into"
            + " google_login_session(id,nonce_hash,browser_hash,expires_at,purpose,status,user_id,auth_session_id,auth_version)"
            + " values(?,?,?,?,?,'NEW',?,?,?)",
        id,
        hash(nonce),
        hash(browser),
        Timestamp.from(expires),
        purpose,
        user == null ? null : user.getId(),
        sid,
        user == null ? null : user.getAuthVersion());
    return new Start(id, client, nonce, expires);
  }

  @Transactional
  public Start startLink(String password, String browser) {
    users.lockRoleChanges();
    var actor = Permissions.actor();
    var user = users.findLockedByEmail(actor.getEmail()).orElseThrow(this::invalid);
    checkBrand(user);
    UUID sid = authSessions.currentId();
    authSessions.require(sid, user, true);
    if (user.getPasswordHash() == null || !encoder.matches(password, user.getPasswordHash()))
      throw invalid();
    if (identities
        .findByUserIdAndProvider(user.getId(), ExternalIdentityProvider.GOOGLE)
        .isPresent()) throw conflict("Google is already linked");
    return create(browser, "LINK", user, sid);
  }

  @Transactional
  public Result finish(UUID id, String browser, Jwt proof) {
    verifier.requireConfigured();
    users.lockRoleChanges();
    String email = normalize(proof.getClaimAsString("email"));
    users.lockRegistrationEmail(email);
    var c = challenge(id, browser);
    requireNew(c, "LOGIN", proof);
    var identity =
        identities
            .findByProviderAndProviderSubject(ExternalIdentityProvider.GOOGLE, proof.getSubject())
            .orElse(null);
    if (identity != null) {
      var user = users.findLockedByEmail(identity.getUser().getEmail()).orElseThrow(this::invalid);
      checkBrand(user);
      LoginResponse response = issuer.issue(user, "GOOGLE");
      consume(id);
      return authenticated(response);
    }
    User existing = users.findLockedByEmail(email).orElse(null);
    if (existing != null) {
      if (!existing.isEmailVerified())
        throw new BusinessException(
            ErrorCode.EMAIL_NOT_VERIFIED, "Complete email verification first");
      throw new BusinessException(
          ErrorCode.GOOGLE_ACCOUNT_LINK_REQUIRED,
          "Authenticate your existing Brand account before linking Google");
    }
    String name = proof.getClaimAsString("name");
    if (name == null || name.isBlank()) name = "Brand";
    name = name.trim();
    name = name.substring(0, Math.min(150, name.length()));
    Instant expires = c.expires.isBefore(proof.getExpiresAt()) ? c.expires : proof.getExpiresAt();
    jdbc.update(
        "update google_login_session set"
            + " status='REGISTRATION_REQUIRED',consumed_at=?,google_subject=?,google_email=?,google_name=?,expires_at=?"
            + " where id=?",
        Timestamp.from(clock.instant()),
        proof.getSubject(),
        email,
        name,
        Timestamp.from(expires),
        id);
    return new Result("REGISTRATION_REQUIRED", id, email, name, expires, null);
  }

  @Transactional
  public Result complete(Complete request, String browser) {
    registration.validate(request.policyAcceptance());
    RegistrationSupport.phone(request.phoneNumber());
    verifier.requireConfigured();
    users.lockRoleChanges();
    // Read identity metadata first, then take the same email advisory lock as password
    // registration.
    var initial =
        jdbc.queryForList(
            "select google_email from google_login_session where id=?", request.sessionId());
    if (initial.isEmpty()) throw invalid();
    if (initial.getFirst().get("google_email") == null) {
      var previous = challenge(request.sessionId(), browser);
      if ("COMPLETED".equals(previous.status))
        throw conflict("Google registration already processed");
      throw invalid();
    }
    String email = (String) initial.getFirst().get("google_email");
    users.lockRegistrationEmail(email);
    var c = challenge(request.sessionId(), browser);
    if (!"LOGIN".equals(c.purpose) || !"REGISTRATION_REQUIRED".equals(c.status))
      throw conflict("Google registration already processed");
    if (users.existsByEmail(email)
        || identities
            .findByProviderAndProviderSubject(ExternalIdentityProvider.GOOGLE, c.subject)
            .isPresent())
      throw new BusinessException(
          ErrorCode.EMAIL_ALREADY_EXISTS, "Account was registered while completing Google signup");
    User user =
        users.saveAndFlush(
            User.builder()
                .email(email)
                .fullName(request.fullName().trim())
                .role(Role.BRAND)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build());
    registration.brand(user, request.businessName(), request.phoneNumber());
    registration.consent(user, request.policyAcceptance(), "GOOGLE_REGISTRATION");
    identities.saveAndFlush(
        ExternalIdentity.builder()
            .user(user)
            .provider(ExternalIdentityProvider.GOOGLE)
            .providerSubject(c.subject)
            .providerEmail(email)
            .emailVerified(true)
            .build());
    LoginResponse response = issuer.issue(user, "GOOGLE");
    consume(c.id);
    return authenticated(response);
  }

  @Transactional
  public void confirmLink(UUID id, String browser, Jwt proof) {
    verifier.requireConfigured();
    users.lockRoleChanges();
    User actor = Permissions.actor();
    User user = users.findLockedByEmail(actor.getEmail()).orElseThrow(this::invalid);
    checkBrand(user);
    UUID sid = authSessions.currentId();
    authSessions.require(sid, user, true);
    var c = challenge(id, browser);
    requireNew(c, "LINK", proof);
    if (!user.getId().equals(c.user)
        || !sid.equals(c.authSession)
        || c.version == null
        || c.version != user.getAuthVersion()) throw invalid();
    if (!user.getEmail().equals(normalize(proof.getClaimAsString("email"))))
      throw new BusinessException(
          ErrorCode.IDENTITY_MISMATCH, "Google email must match this Brand account");
    if (identities
            .findByProviderAndProviderSubject(ExternalIdentityProvider.GOOGLE, proof.getSubject())
            .isPresent()
        || identities
            .findByUserIdAndProvider(user.getId(), ExternalIdentityProvider.GOOGLE)
            .isPresent()) throw conflict("Google identity is already linked");
    identities.saveAndFlush(
        ExternalIdentity.builder()
            .user(user)
            .provider(ExternalIdentityProvider.GOOGLE)
            .providerSubject(proof.getSubject())
            .providerEmail(user.getEmail())
            .emailVerified(true)
            .build());
    audit.recordAs(
        user.getId(),
        "LINK_GOOGLE_IDENTITY",
        "User",
        user.getId(),
        null,
        Map.of("provider", "GOOGLE"),
        "Brand reauthenticated and linked Google");
    consume(id);
  }

  private Challenge challenge(UUID id, String browser) {
    var rows =
        jdbc.query(
            "select * from google_login_session where id=? for update",
            (rs, n) ->
                new Challenge(
                    id,
                    rs.getString("nonce_hash"),
                    rs.getString("browser_hash"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("consumed_at") != null,
                    rs.getString("purpose"),
                    rs.getString("status"),
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("auth_session_id", UUID.class),
                    (Long) rs.getObject("auth_version"),
                    rs.getString("google_subject"),
                    rs.getString("google_email"),
                    rs.getString("google_name")),
            id);
    if (rows.isEmpty()) throw invalid();
    var c = rows.getFirst();
    if (browser == null || !c.browser.equals(hash(browser)) || !c.expires.isAfter(clock.instant()))
      throw invalid();
    return c;
  }

  private void requireNew(Challenge c, String purpose, Jwt proof) {
    if (!purpose.equals(c.purpose)) throw invalid();
    if (c.consumed || !"NEW".equals(c.status)) throw conflict("Start Google Login again");
    if (proof.getExpiresAt() == null
        || !proof.getExpiresAt().isAfter(clock.instant())
        || !c.nonce.equals(hash(proof.getClaimAsString("nonce")))) throw invalid();
  }

  private void checkBrand(User u) {
    if (u.getRole() != Role.BRAND)
      throw new BusinessException(ErrorCode.ACCESS_DENIED, "Brand required");
    if (!u.isEnabled()) throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "Account disabled");
    if (!u.isAccountNonLocked())
      throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "Account locked");
    if (!u.isEmailVerified())
      throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED, "Verify email first");
  }

  private void consume(UUID id) {
    jdbc.update(
        "update google_login_session set"
            + " consumed_at=?,status='COMPLETED',google_subject=null,google_email=null,google_name=null"
            + " where id=?",
        Timestamp.from(clock.instant()),
        id);
  }

  private Result authenticated(LoginResponse response) {
    return new Result("AUTHENTICATED", null, null, null, null, response);
  }

  private String normalize(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private BusinessException invalid() {
    return new BusinessException(ErrorCode.UNAUTHENTICATED, "Invalid or expired Google session");
  }

  private BusinessException conflict(String text) {
    return new BusinessException(ErrorCode.GOOGLE_LOGIN_REPLAY, text);
  }

  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
