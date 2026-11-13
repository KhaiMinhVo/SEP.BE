package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.auth.model.ExternalIdentityProvider;
import com.influencermatch.backend.auth.repository.ExternalIdentityRepository;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.creator.dto.CreatorClaimDtos.*;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreatorClaimService {
  @org.springframework.beans.factory.annotation.Autowired private CreatorConflictAudit conflicts;

  @org.springframework.beans.factory.annotation.Autowired
  private CreatorInvitationRepository invitations;

  @org.springframework.beans.factory.annotation.Autowired private java.time.Clock clock;
  @org.springframework.beans.factory.annotation.Autowired private ManualClaimGuard guard;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.influencermatch.backend.notification.service.NotificationService notificationService;
  private final CreatorRepository creators;
  private final CreatorMasterRepository masters;
  private final CreatorClaimRepository claims;
  private final ExternalIdentityRepository identities;
  private final UserRepository users;
  private final AuditService audit;
  private final EntityManager em;
  private final Validator validator;
  private final Optional<com.influencermatch.backend.auth.service.DevCreatorLoginService>
      devCreatorLogin;

  @Transactional(noRollbackFor = CreatorOwnershipConflictException.class)
  @PreAuthorize("hasAuthority('CLAIM_CREATOR_PROFILE')")
  public ClaimResponse claim(UUID creatorId, ClaimRequest request) {
    Permissions.require(Permission.CLAIM_CREATOR_PROFILE);
    validate(request);
    users.lockRoleChanges();
    guard.users(Permissions.actor().getId(), Permission.CLAIM_CREATOR_PROFILE);
    User claimant = verifiedCreator(Permissions.actor().getId());
    guard.identity(creatorId);
    Creator creator = lockedCreator(creatorId);
    if (creator.getMaster() != null || creator.getSourceType() == CreatorSourceType.AUTHORIZED)
      throw conflicts.reject(
          creatorId, null, ErrorCode.CREATOR_ALREADY_LINKED, "Channel already has an owner");
    var applicable = guard.invitations(creator, claimant, null);
    guard.read(Permission.CLAIM_CREATOR_PROFILE);
    if (claims.existsByCreatorIdAndClaimantIdAndStatus(
        creatorId, claimant.getId(), CreatorClaimStatus.PENDING))
      throw conflict("You already have a pending claim for this channel");
    CreatorClaim claim =
        claims.saveAndFlush(
            CreatorClaim.builder()
                .creator(creator)
                .claimant(claimant)
                .evidenceUrls(List.copyOf(request.evidenceUrls()))
                .note(request.note())
                .invitation(applicable.isEmpty() ? null : applicable.getFirst())
                .status(CreatorClaimStatus.PENDING)
                .build());
    audit.record(
        "SUBMIT_CREATOR_CLAIM",
        "CreatorClaim",
        claim.getId(),
        null,
        Map.of("status", "PENDING", "creatorId", creatorId, "claimantId", claimant.getId()),
        "Creator nộp bằng chứng xác minh quyền sở hữu kênh");
    return response(claim);
  }

  @Transactional(readOnly = true)
  @PreAuthorize("hasAuthority('VIEW_OWN_CREATOR_CLAIM')")
  public PageResponse<ClaimResponse> mine(CreatorClaimStatus status, int page, int size) {
    Permissions.require(Permission.VIEW_OWN_CREATOR_CLAIM);
    return list(status, null, Permissions.actor().getId(), page, size);
  }

  @Transactional(readOnly = true)
  @PreAuthorize("hasAuthority('REVIEW_CREATOR_CLAIM')")
  public PageResponse<ClaimResponse> pending(
      CreatorClaimStatus status, UUID creatorId, UUID claimantId, int page, int size) {
    guard.read(Permission.REVIEW_CREATOR_CLAIM);
    return list(status, creatorId, claimantId, page, size);
  }

  private PageResponse<ClaimResponse> list(
      CreatorClaimStatus status, UUID creatorId, UUID claimantId, int page, int size) {
    Specification<CreatorClaim> filter =
        (r, q, cb) -> {
          List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
          if (status != null) predicates.add(cb.equal(r.get("status"), status));
          if (creatorId != null) predicates.add(cb.equal(r.get("creator").get("id"), creatorId));
          if (claimantId != null) predicates.add(cb.equal(r.get("claimant").get("id"), claimantId));
          return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    return PageResponse.from(
        claims
            .findAll(
                filter,
                PageRequest.of(
                    Math.max(0, page),
                    Math.min(100, Math.max(1, size)),
                    Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))))
            .map(this::response));
  }

  @Transactional(readOnly = true)
  @PreAuthorize("hasAuthority('REVIEW_CREATOR_CLAIM')")
  public ClaimResponse detail(UUID id) {
    guard.read(Permission.REVIEW_CREATOR_CLAIM);
    return response(findClaim(id));
  }

  @Transactional(noRollbackFor = CreatorOwnershipConflictException.class)
  @PreAuthorize("hasAuthority('REVIEW_CREATOR_CLAIM')")
  public ClaimResponse review(UUID id, ReviewRequest request) {
    Permissions.require(Permission.REVIEW_CREATOR_CLAIM);
    validate(request);
    users.lockRoleChanges();
    CreatorClaim claim = findClaim(id);
    guard.users(claim.getClaimant().getId(), Permission.REVIEW_CREATOR_CLAIM);
    guard.identity(claim.getCreator().getId());
    Creator creator = lockedCreator(claim.getCreator().getId());
    em.refresh(claim);
    em.refresh(claim.getClaimant());
    if (request.decision() == Decision.APPROVED
        && claim.getStatus() == CreatorClaimStatus.PENDING
        && (creator.getMaster() != null || creator.getSourceType() == CreatorSourceType.AUTHORIZED))
      throw conflicts.reject(
          creator.getId(), null, ErrorCode.CREATOR_ALREADY_LINKED, "Channel already has an owner");
    var applicable =
        request.decision() == Decision.APPROVED
            ? guard.invitations(creator, claim.getClaimant(), claim)
            : List.<CreatorInvitation>of();
    guard.claim(id);
    guard.read(Permission.REVIEW_CREATOR_CLAIM);
    em.refresh(claim);
    if (claim.getStatus() != CreatorClaimStatus.PENDING)
      throw conflict("Only a PENDING claim can be reviewed");
    User reviewer = Permissions.actor();
    if (request.decision() == Decision.APPROVED) {
      User claimant = verifiedCreator(claim.getClaimant().getId());
      if (creator.getMaster() != null || creator.getSourceType() == CreatorSourceType.AUTHORIZED)
        throw conflicts.reject(
            creator.getId(),
            null,
            ErrorCode.CREATOR_ALREADY_LINKED,
            "Channel already has an owner");
      CreatorMaster master =
          masters
              .findByUserId(claimant.getId())
              .orElseGet(
                  () ->
                      masters.save(
                          CreatorMaster.builder()
                              .user(claimant)
                              .canonicalName(
                                  claimant.getFullName() == null
                                      ? "Creator"
                                      : claimant.getFullName())
                              .status("ACTIVE")
                              .build()));
      if (!"ACTIVE".equals(master.getStatus()))
        throw new ForbiddenException("Creator Master is not active");
      creator.setMaster(master);
      applicable.forEach(i -> i.setStatus("ACCEPTED"));
      creator.setSourceType(CreatorSourceType.AUTHORIZED);
      creator.setAuthorizedAt(LocalDateTime.now(clock));
      guard.sync(creator);
      claim.setStatus(CreatorClaimStatus.APPROVED);
      // Locking the channel serializes every review, including automatic rejection of competing
      // requests.
      for (CreatorClaim other :
          claims.findByCreatorIdAndStatus(creator.getId(), CreatorClaimStatus.PENDING)) {
        if (other.getId().equals(claim.getId())) continue;
        em.refresh(other);
        if (other.getStatus() != CreatorClaimStatus.PENDING) continue;
        decide(
            other,
            CreatorClaimStatus.REJECTED,
            reviewer,
            "Quyền sở hữu kênh đã được xác nhận cho người nhận claim khác");
        audit.record(
            "REJECT_CREATOR_CLAIM",
            "CreatorClaim",
            other.getId(),
            Map.of("status", "PENDING"),
            Map.of("status", "REJECTED"),
            other.getReason());
        if (notificationService != null && other.getClaimant() != null) {
          notificationService.createAndDispatch(
              other.getClaimant().getId(),
              com.influencermatch.backend.notification.enums.NotificationType.CLAIM_REJECTED,
              "Yêu cầu quản lý kênh Creator bị từ chối",
              "Yêu cầu nhận quyền quản lý kênh Creator của bạn đã bị từ chối do quyền sở hữu đã được xác nhận cho người khác.",
              "CREATOR_CLAIM",
              other.getId());
        }
      }
    } else claim.setStatus(CreatorClaimStatus.REJECTED);
    decide(claim, claim.getStatus(), reviewer, request.reason());
    if (notificationService != null && claim.getClaimant() != null) {
      if (claim.getStatus() == CreatorClaimStatus.APPROVED) {
        notificationService.createAndDispatch(
            claim.getClaimant().getId(),
            com.influencermatch.backend.notification.enums.NotificationType.CLAIM_APPROVED,
            "Yêu cầu quản lý kênh Creator được duyệt",
            "Yêu cầu nhận quyền quản lý kênh Creator của bạn đã được quản trị viên phê duyệt.",
            "CREATOR_CLAIM",
            claim.getId());
      } else {
        notificationService.createAndDispatch(
            claim.getClaimant().getId(),
            com.influencermatch.backend.notification.enums.NotificationType.CLAIM_REJECTED,
            "Yêu cầu quản lý kênh Creator bị từ chối",
            request.reason() != null && !request.reason().isBlank()
                ? "Yêu cầu nhận quyền quản lý kênh Creator của bạn đã bị từ chối do: " + request.reason()
                : "Yêu cầu nhận quyền quản lý kênh Creator của bạn đã bị từ chối.",
            "CREATOR_CLAIM",
            claim.getId());
      }
    }
    var reviewData = new LinkedHashMap<String, Object>();
    reviewData.put("status", claim.getStatus().name());
    reviewData.put("creatorId", creator.getId());
    reviewData.put("claimantId", claim.getClaimant().getId());
    if (claim.getStatus() == CreatorClaimStatus.APPROVED)
      reviewData.put("masterId", creator.getMaster().getId());
    audit.record(
        "REVIEW_CREATOR_CLAIM",
        "CreatorClaim",
        id,
        Map.of("status", "PENDING"),
        reviewData,
        request.reason());
    claims.flush();
    return response(claim);
  }

  @Transactional(noRollbackFor = CreatorOwnershipConflictException.class)
  @PreAuthorize("hasAuthority('REVIEW_CREATOR_CLAIM')")
  public ClaimResponse revoke(UUID id, RevokeRequest request) {
    Permissions.require(Permission.REVIEW_CREATOR_CLAIM);
    validate(request);
    users.lockRoleChanges();
    CreatorClaim claim = findClaim(id);
    guard.users(claim.getClaimant().getId(), Permission.REVIEW_CREATOR_CLAIM);
    guard.identity(claim.getCreator().getId());
    Creator creator = lockedCreator(claim.getCreator().getId());
    guard.claim(id);
    guard.read(Permission.REVIEW_CREATOR_CLAIM);
    em.refresh(claim);
    if (claim.getStatus() != CreatorClaimStatus.APPROVED)
      throw conflict("Only the active approved claim can be revoked");
    if (creator.getOwner() == null
        || !creator.getOwner().getId().equals(claim.getClaimant().getId()))
      throw conflicts.reject(
          creator.getId(),
          null,
          ErrorCode.CREATOR_ALREADY_LINKED,
          "Claim no longer matches current ownership");
    UUID originalMaster = creator.getMaster().getId();
    claim.setStatus(CreatorClaimStatus.REVOKED);
    claim.setRevokedBy(Permissions.actor());
    claim.setRevokeReason(request.reason());
    claim.setRevokedAt(LocalDateTime.now(clock));
    if (!guard.remainingProof(creator.getId())) {
      creator.setOwner(null);
      creator.setSourceType(CreatorSourceType.PUBLIC);
      creator.setAuthorizedAt(null);
      guard.sync(creator);
    }
    audit.record(
        "REVOKE_CREATOR_CLAIM",
        "CreatorClaim",
        id,
        Map.of("status", "APPROVED"),
        Map.of("status", "REVOKED", "creatorId", creator.getId(), "masterId", originalMaster),
        request.reason());
    claims.flush();
    return response(claim);
  }

  private User verifiedCreator(UUID id) {
    User user = users.findById(id).orElseThrow(() -> conflict("Claimant no longer exists"));
    em.refresh(user);
    if (user.getRole() != Role.CREATOR || user.getStatus() != UserStatus.ACTIVE)
      throw conflict("Claimant must be an active Creator");
    if (identities.findByUserIdAndProvider(id, ExternalIdentityProvider.DEV_MOCK).isPresent()
        && devCreatorLogin.isEmpty())
      throw new ForbiddenException("Development mock Creator login is disabled");
    if (!user.isEmailVerified()) throw new ForbiddenException("A verified email is required");
    return user;
  }

  private Creator lockedCreator(UUID id) {
    Creator creator =
        creators.findLockedById(id).orElseThrow(() -> new NotFoundException("Creator not found"));
    em.refresh(creator);
    return creator;
  }

  private CreatorClaim findClaim(UUID id) {
    return claims.findById(id).orElseThrow(() -> new NotFoundException("Creator claim not found"));
  }

  private void decide(CreatorClaim claim, CreatorClaimStatus status, User reviewer, String reason) {
    claim.setStatus(status);
    claim.setReviewer(reviewer);
    claim.setReason(reason);
    claim.setReviewedAt(LocalDateTime.now(clock));
  }

  private ClaimResponse response(CreatorClaim c) {
    return new ClaimResponse(
        c.getId(),
        c.getCreator().getId(),
        c.getClaimant().getId(),
        c.getEvidenceUrls(),
        c.getNote(),
        c.getStatus(),
        c.getReviewer() == null ? null : c.getReviewer().getId(),
        c.getReason(),
        c.getReviewedAt(),
        c.getRevokedBy() == null ? null : c.getRevokedBy().getId(),
        c.getRevokeReason(),
        c.getRevokedAt(),
        c.getCreatedAt(),
        c.getUpdatedAt());
  }

  private void validate(Object request) {
    var violations = validator.validate(request);
    if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
  }

  private ConflictException conflict(String reason) {
    return new ConflictException(ErrorCode.CREATOR_CLAIM_CONFLICT, reason);
  }
}
