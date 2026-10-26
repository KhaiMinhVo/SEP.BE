package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.model.RecommendationRun;
import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import com.influencermatch.backend.creator.dto.InvitationResponse;
import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.CreatorInvitation;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.CreatorInvitationRepository;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.creator.service.CreatorInvitationService;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.relationship.dto.*;
import com.influencermatch.backend.relationship.enums.RelationshipStage;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistSource;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.BrandCreatorRelationship;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ShortlistServiceImpl implements ShortlistService {

  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}");

  private final ShortlistItemRepository shortlistItemRepository;
  private final CampaignOwnershipService ownership;
  private final CreatorRepository creatorRepository;
  private final UserRepository userRepository;
  private final RecommendationRunRepository recommendationRunRepository;
  private final PublicCreatorMetricRepository publicCreatorMetricRepository;
  private final BrandCreatorRelationshipRepository relationshipRepository;
  private final CreatorInvitationService creatorInvitationService;
  private final CreatorInvitationRepository creatorInvitationRepository;
  private final CrawlerServiceClient crawlerServiceClient;

  @Autowired
  public ShortlistServiceImpl(
      ShortlistItemRepository shortlistItemRepository,
      CampaignOwnershipService ownership,
      CreatorRepository creatorRepository,
      UserRepository userRepository,
      RecommendationRunRepository recommendationRunRepository,
      PublicCreatorMetricRepository publicCreatorMetricRepository,
      BrandCreatorRelationshipRepository relationshipRepository,
      @Autowired(required = false) CreatorInvitationService creatorInvitationService,
      @Autowired(required = false) CreatorInvitationRepository creatorInvitationRepository,
      @Autowired(required = false) CrawlerServiceClient crawlerServiceClient) {
    this.shortlistItemRepository = shortlistItemRepository;
    this.ownership = ownership;
    this.creatorRepository = creatorRepository;
    this.userRepository = userRepository;
    this.recommendationRunRepository = recommendationRunRepository;
    this.publicCreatorMetricRepository = publicCreatorMetricRepository;
    this.relationshipRepository = relationshipRepository;
    this.creatorInvitationService = creatorInvitationService;
    this.creatorInvitationRepository = creatorInvitationRepository;
    this.crawlerServiceClient = crawlerServiceClient;
  }

  public ShortlistServiceImpl(
      ShortlistItemRepository shortlistItemRepository,
      CampaignOwnershipService ownership,
      CreatorRepository creatorRepository,
      UserRepository userRepository,
      RecommendationRunRepository recommendationRunRepository,
      PublicCreatorMetricRepository publicCreatorMetricRepository,
      BrandCreatorRelationshipRepository relationshipRepository,
      CreatorInvitationService creatorInvitationService,
      CreatorInvitationRepository creatorInvitationRepository) {
    this(
        shortlistItemRepository,
        ownership,
        creatorRepository,
        userRepository,
        recommendationRunRepository,
        publicCreatorMetricRepository,
        relationshipRepository,
        creatorInvitationService,
        creatorInvitationRepository,
        null);
  }

  public ShortlistServiceImpl(
      ShortlistItemRepository shortlistItemRepository,
      CampaignOwnershipService ownership,
      CreatorRepository creatorRepository,
      UserRepository userRepository,
      RecommendationRunRepository recommendationRunRepository,
      PublicCreatorMetricRepository publicCreatorMetricRepository,
      BrandCreatorRelationshipRepository relationshipRepository) {
    this(
        shortlistItemRepository,
        ownership,
        creatorRepository,
        userRepository,
        recommendationRunRepository,
        publicCreatorMetricRepository,
        relationshipRepository,
        null,
        null,
        null);
  }


  /**
   * FLOW SL-01: 1-Click Add to Shortlist Cart (Idempotent Handling)
   */
  @Override
  @Transactional
  public ShortlistItemDto addCreatorToShortlist(UUID campaignId, AddShortlistItemRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    Campaign campaign = ownership.requireOwned(campaignId);
    UUID userId = Permissions.actor().getId();

    UUID targetCreatorId = request.getEffectiveCreatorId();
    if (targetCreatorId == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Creator ID is required");
    }

    ShortlistPriority priority =
        request.getPriority() != null ? request.getPriority() : ShortlistPriority.MEDIUM;
    ShortlistSource source =
        request.getSource() != null ? request.getSource() : ShortlistSource.MANUAL_DISCOVERY;

    // Idempotent Shortlist Cart Check (Chống lỗi 409)
    Optional<ShortlistItem> existingOpt =
        shortlistItemRepository.findByCampaignIdAndCreatorId(campaignId, targetCreatorId);
    if (existingOpt.isPresent()) {
      ShortlistItem existing = existingOpt.get();
      // Nếu trước đó bị REMOVED, phục hồi về SAVED
      if (existing.getStatus() == ShortlistStatus.REMOVED) {
        existing.setStatus(ShortlistStatus.SAVED);
        if (request.getNote() != null) {
          existing.setNote(request.getNote());
        }
        if (request.getPriority() != null) {
          existing.setPriority(request.getPriority());
        }
        existing = shortlistItemRepository.save(existing);
      } else if (request.getPriority() != null) {
        existing.setPriority(request.getPriority());
        existing = shortlistItemRepository.save(existing);
      }

      long totalCount =
          shortlistItemRepository.countByCampaignIdAndStatusNot(campaignId, ShortlistStatus.REMOVED);

      ShortlistItemDto dto = mapToDto(existing);
      dto.setAction("ALREADY_EXISTS");
      dto.setIsInShortlist(true);
      dto.setTotalShortlistCount(totalCount);
      dto.setMessage("Ứng viên đã có sẵn trong Shortlist");
      return dto;
    }

    Creator creator =
        creatorRepository
            .findById(targetCreatorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Creator not found"));

    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "User not found"));

    RecommendationRun run = null;
    if (request.getRecommendationRunId() != null) {
      run = recommendationRunRepository.findById(request.getRecommendationRunId()).orElse(null);
    }

    ShortlistItem item =
        ShortlistItem.builder()
            .campaign(campaign)
            .creator(creator)
            .master(creator.getMaster())
            .user(user)
            .priority(priority)
            .status(ShortlistStatus.SAVED)
            .source(source)
            .recommendationRun(run)
            .note(request.getNote())
            .addedAt(LocalDateTime.now())
            .build();

    item = shortlistItemRepository.save(item);

    if (crawlerServiceClient != null) {
      try {
        crawlerServiceClient.triggerShortlistCrawlAsync(
            creator.getPlatform(),
            creator.getUserName(),
            creator.getId(),
            campaignId
        );
      } catch (Exception e) {
        log.warn("Could not trigger async shortlist crawl for creator {}: {}", creator.getId(), e.getMessage());
      }
    }

    long totalCount =
        shortlistItemRepository.countByCampaignIdAndStatusNot(campaignId, ShortlistStatus.REMOVED);
    String creatorName =
        creator.getDisplayName() != null ? creator.getDisplayName() : creator.getUserName();

    ShortlistItemDto dto = mapToDto(item);
    dto.setAction("ADDED");
    dto.setIsInShortlist(true);
    dto.setTotalShortlistCount(totalCount);
    dto.setMessage("Đã thêm " + creatorName + " vào Shortlist");
    return dto;
  }

  /**
   * FLOW SL-02: View Shortlist by Campaign with filtering
   */
  @Override
  @Transactional(readOnly = true)
  public Page<ShortlistItemDto> getShortlistByCampaign(UUID campaignId, Pageable pageable) {
    return getShortlistByCampaign(campaignId, null, null, null, pageable);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ShortlistItemDto> getShortlistByCampaign(
      UUID campaignId,
      ShortlistStatus status,
      ShortlistPriority priority,
      String platform,
      Pageable pageable) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(campaignId);

    Specification<ShortlistItem> spec =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          predicates.add(cb.equal(root.get("campaign").get("id"), campaignId));

          if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
          } else {
            // Mặc định ẩn các item đã REMOVED
            predicates.add(cb.notEqual(root.get("status"), ShortlistStatus.REMOVED));
          }

          if (priority != null) {
            predicates.add(cb.equal(root.get("priority"), priority));
          }

          if (platform != null && !platform.isBlank()) {
            predicates.add(cb.equal(cb.upper(root.get("creator").get("platform")), platform.toUpperCase()));
          }

          return cb.and(predicates.toArray(new Predicate[0]));
        };

    return shortlistItemRepository.findAll(spec, pageable).map(this::mapToDto);
  }

  /**
   * FLOW SL-02: Update Shortlist Item Priority, Note, or Status
   */
  @Override
  @Transactional
  public ShortlistItemDto updateShortlistItem(
      UUID campaignId, UUID shortlistItemId, UpdateShortlistItemRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(campaignId);

    ShortlistItem item =
        shortlistItemRepository
            .findByIdAndCampaignId(shortlistItemId, campaignId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Shortlist item not found"));

    if (item.getStatus() == ShortlistStatus.REMOVED && request.getStatus() != ShortlistStatus.SAVED) {
      throw new BusinessException(
          ErrorCode.INVALID_COLLABORATION_STATE, "Cannot update a removed shortlist item");
    }

    if (request.getPriority() != null) {
      item.setPriority(request.getPriority());
    }

    if (request.getNote() != null) {
      item.setNote(request.getNote());
    }

    if (request.getStatus() != null) {
      item.setStatus(request.getStatus());
    }

    item = shortlistItemRepository.save(item);
    return mapToDto(item);
  }

  /**
   * FLOW SL-02: Batch Update Status (Ví dụ chuyển hàng loạt sang READY_TO_CONTACT)
   */
  @Override
  @Transactional
  public List<ShortlistItemDto> batchUpdateStatus(
      UUID campaignId, BatchUpdateStatusRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(campaignId);

    List<ShortlistItem> items =
        shortlistItemRepository.findByCampaignIdAndIdIn(campaignId, request.getItemIds());
    for (ShortlistItem item : items) {
      if (item.getStatus() != ShortlistStatus.REMOVED || request.getStatus() == ShortlistStatus.SAVED) {
        item.setStatus(request.getStatus());
        shortlistItemRepository.save(item);
      }
    }
    return items.stream().map(this::mapToDto).toList();
  }

  /**
   * FLOW SL-02: Soft remove khỏi Shortlist đang hoạt động
   */
  @Override
  @Transactional
  public void removeCreatorFromShortlist(UUID campaignId, UUID shortlistItemId) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(campaignId);

    ShortlistItem item =
        shortlistItemRepository
            .findByIdAndCampaignId(shortlistItemId, campaignId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Shortlist item not found"));

    item.setStatus(ShortlistStatus.REMOVED);
    shortlistItemRepository.save(item);
  }

  /**
   * FLOW SL-04: Contact Selection & Readiness Preview
   */
  @Override
  @Transactional(readOnly = true)
  public ContactReadinessResponse checkContactReadiness(
      UUID campaignId, ContactReadinessRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(campaignId);

    List<ShortlistItem> candidates = new ArrayList<>();
    if (request != null && request.getItemIds() != null && !request.getItemIds().isEmpty()) {
      candidates = shortlistItemRepository.findByCampaignIdAndIdIn(campaignId, request.getItemIds());
    } else if (request != null && request.getCreatorIds() != null && !request.getCreatorIds().isEmpty()) {
      candidates = shortlistItemRepository.findByCampaignIdAndCreatorIdIn(campaignId, request.getCreatorIds());
    } else {
      candidates = shortlistItemRepository.findByCampaignIdAndStatus(campaignId, ShortlistStatus.READY_TO_CONTACT);
    }

    int readyCount = 0;
    int manualCount = 0;
    int unavailableCount = 0;
    int alreadyContactedCount = 0;

    List<ContactReadinessResponse.ContactReadinessItem> readinessItems = new ArrayList<>();

    for (ShortlistItem item : candidates) {
      Creator creator = item.getCreator();
      UUID creatorId = creator.getId();
      String creatorName = creator.getDisplayName() != null ? creator.getDisplayName() : creator.getUserName();

      // Case 5: Đang có lời mời hoặc quan hệ CRM hoạt động
      if (relationshipRepository.existsByCampaignIdAndCreatorId(campaignId, creatorId)) {
        alreadyContactedCount++;
        readinessItems.add(
            ContactReadinessResponse.ContactReadinessItem.builder()
                .shortlistItemId(item.getId())
                .creatorId(creatorId)
                .masterId(creator.getMaster() != null ? creator.getMaster().getId() : null)
                .creatorName(creatorName)
                .platform(creator.getPlatform())
                .avatarUrl(creator.getAvatarUrl())
                .readinessStatus("ALREADY_CONTACTED")
                .contactChannel("EXISTING_INVITATION")
                .contactTarget(null)
                .action("Chặn gửi trùng lặp - Đã có tương tác hợp tác trước đó")
                .canProceed(false)
                .reason("Ứng viên đã có hồ sơ liên hệ trong chiến dịch này")
                .build());
        continue;
      }

      // Case 1: Đã Claim kênh, có Creator Portal nội bộ
      if (creator.getMaster() != null && creator.getMaster().getUser() != null) {
        readyCount++;
        String userEmail = creator.getMaster().getUser().getEmail();
        readinessItems.add(
            ContactReadinessResponse.ContactReadinessItem.builder()
                .shortlistItemId(item.getId())
                .creatorId(creatorId)
                .masterId(creator.getMaster().getId())
                .creatorName(creatorName)
                .platform(creator.getPlatform())
                .avatarUrl(creator.getAvatarUrl())
                .readinessStatus("READY")
                .contactChannel("IN_APP_PORTAL")
                .contactTarget(userEmail)
                .action("Gửi Collaboration Invitation trực tiếp qua Creator Portal")
                .canProceed(true)
                .reason("Kênh đã xác minh quyền sở hữu, gửi thông báo trực tiếp qua Portal")
                .build());
        continue;
      }

      // Case 2: Chưa Claim, nhưng có Email Booking hợp lệ
      Optional<PublicCreatorMetric> metricOpt = publicCreatorMetricRepository.findByCreatorId(creatorId);
      String bookingEmail = null;
      if (metricOpt.isPresent()) {
        bookingEmail = extractEmail(metricOpt.get().getContact());
        if (bookingEmail == null) {
          bookingEmail = extractEmail(metricOpt.get().getContentSummary());
        }
      }

      if (bookingEmail != null) {
        readyCount++;
        readinessItems.add(
            ContactReadinessResponse.ContactReadinessItem.builder()
                .shortlistItemId(item.getId())
                .creatorId(creatorId)
                .masterId(null)
                .creatorName(creatorName)
                .platform(creator.getPlatform())
                .avatarUrl(creator.getAvatarUrl())
                .readinessStatus("READY")
                .contactChannel("SYSTEM_EMAIL_OUTBOX")
                .contactTarget(bookingEmail)
                .action("Gửi Email mời trao đổi chiến dịch qua Outbox hệ thống")
                .canProceed(true)
                .reason("Tìm thấy email booking công khai hợp lệ: " + bookingEmail)
                .build());
        continue;
      }

      // Case 3: Chưa Claim, chỉ có Profile Social URL
      String profileUrl = creator.getProfileUrl();
      if (profileUrl != null && !profileUrl.isBlank()) {
        manualCount++;
        readinessItems.add(
            ContactReadinessResponse.ContactReadinessItem.builder()
                .shortlistItemId(item.getId())
                .creatorId(creatorId)
                .masterId(null)
                .creatorName(creatorName)
                .platform(creator.getPlatform())
                .avatarUrl(creator.getAvatarUrl())
                .readinessStatus("MANUAL_CONTACT")
                .contactChannel("MANUAL_SOCIAL_LINK")
                .contactTarget(profileUrl)
                .action("Cung cấp đường dẫn mạng xã hội để Brand liên hệ thủ công")
                .canProceed(false)
                .reason("Chưa tìm thấy email booking, vui lòng nhắn tin trực tiếp qua trang cá nhân")
                .build());
        continue;
      }

      // Case 4: Không có thông tin liên hệ
      unavailableCount++;
      readinessItems.add(
          ContactReadinessResponse.ContactReadinessItem.builder()
              .shortlistItemId(item.getId())
              .creatorId(creatorId)
              .masterId(null)
              .creatorName(creatorName)
              .platform(creator.getPlatform())
              .avatarUrl(creator.getAvatarUrl())
              .readinessStatus("UNAVAILABLE")
              .contactChannel("NONE")
              .contactTarget(null)
              .action("Cảnh báo: Không có thông tin liên hệ, nên tìm ứng viên thay thế")
              .canProceed(false)
              .reason("Không tìm thấy email hoặc đường dẫn trang cá nhân của Creator")
              .build());
    }

    return ContactReadinessResponse.builder()
        .totalSelected(candidates.size())
        .readyCount(readyCount)
        .manualCount(manualCount)
        .unavailableCount(unavailableCount)
        .alreadyContactedCount(alreadyContactedCount)
        .items(readinessItems)
        .build();
  }

  private String extractEmail(String text) {
    if (text == null || text.isBlank()) return null;
    Matcher matcher = EMAIL_PATTERN.matcher(text);
    if (matcher.find()) {
      return matcher.group();
    }
    return null;
  }

  private ShortlistItemDto mapToDto(ShortlistItem item) {
    UUID masterId = null;
    if (item.getMaster() != null) {
      masterId = item.getMaster().getId();
    } else if (item.getCreator() != null && item.getCreator().getMaster() != null) {
      masterId = item.getCreator().getMaster().getId();
    }

    String creatorName = null;
    String platform = null;
    String avatarUrl = null;
    if (item.getCreator() != null) {
      creatorName =
          item.getCreator().getDisplayName() != null
              ? item.getCreator().getDisplayName()
              : item.getCreator().getUserName();
      platform = item.getCreator().getPlatform();
      avatarUrl = item.getCreator().getAvatarUrl();
    }

    Long followers = null;
    BigDecimal engagementRate = null;
    String niche = null;
    if (item.getCreator() != null) {
      Optional<PublicCreatorMetric> metricOpt =
          publicCreatorMetricRepository.findByCreatorId(item.getCreator().getId());
      if (metricOpt.isPresent()) {
        followers = metricOpt.get().getFollowers();
        engagementRate = metricOpt.get().getEngagementRate();
        niche = metricOpt.get().getNiche();
      }
    }

    return ShortlistItemDto.builder()
        .id(item.getId())
        .campaignId(item.getCampaign().getId())
        .creatorId(item.getCreator().getId())
        .masterId(masterId)
        .creatorName(creatorName)
        .platform(platform)
        .avatarUrl(avatarUrl)
        .followers(followers)
        .engagementRate(engagementRate)
        .niche(niche)
        .priority(item.getPriority())
        .status(item.getStatus())
        .source(item.getSource())
        .recommendationRunId(
            item.getRecommendationRun() != null ? item.getRecommendationRun().getId() : null)
        .note(item.getNote())
        .addedAt(item.getAddedAt())
        .isInShortlist(item.getStatus() != ShortlistStatus.REMOVED)
        .build();
  }

  @Override
  @Transactional
  public ShortlistOutreachResponse outreachShortlist(
      UUID campaignId, ShortlistOutreachRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    Campaign campaign = ownership.requireOwned(campaignId);
    User actor = Permissions.actor();
    BrandProfile brand = campaign.getBrandProfile();

    if (request.getShortlistItemIds() == null || request.getShortlistItemIds().isEmpty()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Shortlist item IDs cannot be empty");
    }

    List<ShortlistItem> items =
        shortlistItemRepository.findByCampaignIdAndIdIn(campaignId, request.getShortlistItemIds());

    if (items.isEmpty()) {
      throw new BusinessException(
          ErrorCode.RESOURCE_NOT_FOUND, "No shortlist items found for campaign " + campaignId);
    }

    List<UUID> relationshipIds = new ArrayList<>();
    List<UUID> dispatchedInvitationIds = new ArrayList<>();
    List<ShortlistOutreachResponse.OutreachItemResult> itemResults = new ArrayList<>();
    int contactedCount = 0;
    LocalDateTime now = LocalDateTime.now();

    for (ShortlistItem item : items) {
      // 1. Chuyển trạng thái ShortlistItem sang CONTACTED
      item.setStatus(ShortlistStatus.CONTACTED);
      shortlistItemRepository.save(item);

      Creator creator = item.getCreator();
      UUID creatorId = creator.getId();
      String creatorName =
          creator.getDisplayName() != null ? creator.getDisplayName() : creator.getUserName();

      // 2. Tạo hoặc cập nhật BrandCreatorRelationship ở giai đoạn CONTACTED
      Optional<BrandCreatorRelationship> existingRelOpt =
          relationshipRepository.findByCampaignIdAndCreatorId(campaignId, creatorId);

      BrandCreatorRelationship rel;
      if (existingRelOpt.isPresent()) {
        rel = existingRelOpt.get();
        if (rel.getStage() == RelationshipStage.SHORTLISTED) {
          rel.setStage(RelationshipStage.CONTACTED);
        }
        if (request.getOutreachMessage() != null) {
          rel.setNotes(request.getOutreachMessage());
        }
        if (request.getProposedFee() != null) {
          rel.setQuotedFee(request.getProposedFee());
        }
        if (request.getCurrency() != null) {
          rel.setCurrency(request.getCurrency());
        }
        if (request.getContactMethod() != null) {
          rel.setContactMethod(request.getContactMethod());
        }
        rel.setShortlistItem(item);
        rel.setLastContactAt(now);
        rel.setUpdatedAt(now);
      } else {
        rel =
            BrandCreatorRelationship.builder()
                .campaign(campaign)
                .creator(creator)
                .master(item.getMaster() != null ? item.getMaster() : creator.getMaster())
                .user(actor)
                .shortlistItem(item)
                .stage(RelationshipStage.CONTACTED)
                .contactMethod(
                    request.getContactMethod() != null
                        ? request.getContactMethod()
                        : "EMAIL_INVITATION")
                .quotedFee(request.getProposedFee())
                .currency(request.getCurrency() != null ? request.getCurrency() : "VND")
                .notes(request.getOutreachMessage())
                .lastContactAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
      }
      rel = relationshipRepository.save(rel);
      relationshipIds.add(rel.getId());
      contactedCount++;

      // 3. Xử lý Lời mời Claim hoặc Thông báo Creator Portal
      UUID invitationId = null;
      String contactStatus;
      String outcomeMessage;

      boolean isClaimed =
          creator.getSourceType() == CreatorSourceType.AUTHORIZED || creator.getMaster() != null;
      if (isClaimed) {
        contactStatus = "PORTAL_NOTIFIED";
        outcomeMessage =
            "Creator chính chủ đã kết nối tài khoản. Đề xuất hợp tác được chuyển trực tiếp vào Creator Portal.";
      } else {
        // Creator chưa Claim (PUBLIC) -> Tìm email và gửi lời mời
        String bookingEmail = null;
        Optional<PublicCreatorMetric> metricOpt =
            publicCreatorMetricRepository.findByCreatorId(creatorId);
        if (metricOpt.isPresent()) {
          bookingEmail = extractEmail(metricOpt.get().getContact());
          if (bookingEmail == null) {
            bookingEmail = extractEmail(metricOpt.get().getContentSummary());
          }
        }
        if (bookingEmail == null && creator.getDisplayName() != null) {
          bookingEmail = extractEmail(creator.getDisplayName());
        }
        if (bookingEmail == null && creator.getUserName() != null) {
          bookingEmail = extractEmail(creator.getUserName());
        }

        if (bookingEmail != null) {
          Optional<CreatorInvitation> pendingOpt =
              creatorInvitationRepository != null
                  ? creatorInvitationRepository
                      .findFirstByTargetCreatorIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                          creatorId, "PENDING", now)
                  : Optional.empty();

          if (pendingOpt.isPresent()) {
            invitationId = pendingOpt.get().getId();
            dispatchedInvitationIds.add(invitationId);
            contactStatus = "PENDING_INVITATION_EXISTS";
            outcomeMessage =
                "Đã có lời mời Claim đang chờ xử lý tới "
                    + bookingEmail
                    + ". Tiếp tục ghi nhận quan hệ CRM.";
          } else if (creatorInvitationService != null && brand != null) {
            try {
              InvitationResponse invResponse =
                  creatorInvitationService.sendInvitation(
                      creatorId, bookingEmail, brand.getId(), campaignId);
              invitationId = invResponse.getId();
              dispatchedInvitationIds.add(invitationId);
              contactStatus = "INVITATION_QUEUED";
              outcomeMessage = "Đã gửi email mời Claim và hợp tác tới: " + bookingEmail;
            } catch (Exception ex) {
              log.warn(
                  "Could not dispatch claim invitation email for creator {}: {}",
                  creatorId,
                  ex.getMessage());
              contactStatus = "INVITATION_FAILED";
              outcomeMessage = "Không thể gửi email tự động: " + ex.getMessage();
            }
          } else {
            contactStatus = "INVITATION_SKIPPED";
            outcomeMessage =
                "Tìm thấy email " + bookingEmail + " nhưng dịch vụ gửi email chưa sẵn sàng.";
          }
        } else if (creator.getProfileUrl() != null && !creator.getProfileUrl().isBlank()) {
          contactStatus = "MANUAL_SOCIAL_LINK";
          outcomeMessage =
              "Chưa có email booking, vui lòng liên hệ trực tiếp qua mạng xã hội: "
                  + creator.getProfileUrl();
        } else {
          contactStatus = "NO_CONTACT_INFO";
          outcomeMessage =
              "Chưa có thông tin liên hệ. Đã lưu quan hệ CRM để Brand chủ động cập nhật sau.";
        }
      }

      itemResults.add(
          ShortlistOutreachResponse.OutreachItemResult.builder()
              .shortlistItemId(item.getId())
              .creatorId(creatorId)
              .creatorName(creatorName)
              .relationshipId(rel.getId())
              .invitationId(invitationId)
              .contactStatus(contactStatus)
              .message(outcomeMessage)
              .build());
    }

    return ShortlistOutreachResponse.builder()
        .campaignId(campaignId)
        .totalRequested(items.size())
        .contactedCount(contactedCount)
        .relationshipIds(relationshipIds)
        .dispatchedInvitationIds(dispatchedInvitationIds)
        .items(itemResults)
        .message("Đã xử lý tiếp cận hợp tác cho " + contactedCount + " Creator")
        .build();
  }
}
