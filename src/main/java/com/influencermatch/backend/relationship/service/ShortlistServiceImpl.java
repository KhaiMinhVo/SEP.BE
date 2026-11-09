package com.influencermatch.backend.relationship.service;

import com.influencermatch.backend.campaign.repository.RecommendationRunRepository;
import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.CreatorInvitationRepository;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.creator.service.CreatorInvitationService;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.relationship.dto.*;
import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import com.influencermatch.backend.relationship.model.ShortlistItem;
import com.influencermatch.backend.relationship.repository.BrandCreatorRelationshipRepository;
import com.influencermatch.backend.relationship.repository.ShortlistItemRepository;
import com.influencermatch.backend.security.permission.Permission;
import com.influencermatch.backend.security.permission.Permissions;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
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
  private com.influencermatch.backend.cooperation.CooperationOutreachService cooperationOutreach;

  private final ShortlistNotificationService notificationService;

  @Autowired private ShortlistItemWriter writer;
  @Autowired private ShortlistProjectionService projection;
  @Autowired private com.influencermatch.backend.campaign.service.CampaignWriteLock writeLocks;

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
      @Autowired(required = false) CrawlerServiceClient crawlerServiceClient,
      @Autowired(required = false) ShortlistNotificationService notificationService) {
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
    this.notificationService = notificationService;
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
      CreatorInvitationRepository creatorInvitationRepository,
      CrawlerServiceClient crawlerServiceClient) {
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
        crawlerServiceClient,
        null);
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
        null,
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
        null,
        null);
  }

  @Override
  public ShortlistItemDto addCreatorToShortlist(UUID id, AddShortlistItemRequest request) {
    return writer.add(id, request);
  }

  @Override
  public BatchAddShortlistResponse addBatch(UUID id, BatchAddShortlistRequest request) {
    writer.preflight(id);
    if (request == null
        || request.creatorIds() == null
        || request.creatorIds().isEmpty()
        || request.creatorIds().size() > 100
        || request.creatorIds().stream().anyMatch(Objects::isNull)
        || new HashSet<>(request.creatorIds()).size() != request.creatorIds().size())
      throw new com.influencermatch.backend.exception.ValidationException(
          "Batch requires 1-100 distinct Creator IDs");
    var results = new ArrayList<BatchAddShortlistResponse.Result>();
    var counts = new LinkedHashMap<String, Long>();
    for (String action : List.of("ADDED", "ALREADY_EXISTS", "RESTORED", "INVALID", "FAILED"))
      counts.put(action, 0L);
    for (UUID creator : request.creatorIds()) {
      BatchAddShortlistResponse.Result result;
      try {
        var dto =
            writer.add(
                id,
                AddShortlistItemRequest.builder()
                    .creatorId(creator)
                    .priority(request.priority())
                    .note(request.note())
                    .source(request.source())
                    .recommendationRunId(request.recommendationRunId())
                    .build());
        result = new BatchAddShortlistResponse.Result(creator, dto.getAction(), dto, null);
      } catch (BusinessException ex) {
        if (ex.code() == ErrorCode.ACCESS_DENIED || ex.code() == ErrorCode.UNAUTHENTICATED)
          throw ex;
        result = new BatchAddShortlistResponse.Result(creator, "INVALID", null, ex.code().name());
      } catch (Exception ex) {
        log.error(
            "Shortlist batch item failed campaignId={} creatorId={} errorType={}",
            id,
            creator,
            ex.getClass().getSimpleName());
        result = new BatchAddShortlistResponse.Result(creator, "FAILED", null, "INTERNAL_ERROR");
      }
      results.add(result);
      counts.compute(result.action(), (k, v) -> v + 1);
    }
    return new BatchAddShortlistResponse(
        id,
        List.copyOf(results),
        counts,
        shortlistItemRepository.countByCampaignIdAndStatusNot(id, ShortlistStatus.REMOVED));
  }

  /** FLOW SL-02: View Shortlist by Campaign with filtering */
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
          predicates.add(cb.equal(root.get("user").get("id"), Permissions.actor().getId()));

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
            predicates.add(
                cb.equal(cb.upper(root.get("creator").get("platform")), platform.toUpperCase()));
          }

          return cb.and(predicates.toArray(new Predicate[0]));
        };

    var page = shortlistItemRepository.findAll(spec, pageable);
    var snapshots = projection.latest(page.getContent());
    return page.map(i -> projection.map(i, snapshots.get(i.getCreator().getId())));
  }

  /** FLOW SL-02: Update Shortlist Item Priority, Note, or Status */
  @Override
  @Transactional
  public ShortlistItemDto updateShortlistItem(
      UUID campaignId, UUID shortlistItemId, UpdateShortlistItemRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(campaignId);
    writeLocks.owned(campaignId);

    ShortlistItem item =
        shortlistItemRepository
            .findLockedByIdAndCampaignId(shortlistItemId, campaignId)
            .filter(this::hasMatchingOwner)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Shortlist item not found"));

    if (item.getStatus() == ShortlistStatus.REMOVED
        && request.getStatus() != ShortlistStatus.SAVED) {
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

  /** FLOW SL-02: Batch Update Status (Ví dụ chuyển hàng loạt sang READY_TO_CONTACT) */
  @Override
  @Transactional
  public List<ShortlistItemDto> batchUpdateStatus(
      UUID campaignId, BatchUpdateStatusRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(campaignId);
    writeLocks.owned(campaignId);

    List<ShortlistItem> items =
        shortlistItemRepository.findByCampaignIdAndIdIn(campaignId, request.getItemIds()).stream()
            .filter(this::hasMatchingOwner)
            .toList();
    for (ShortlistItem item :
        items.stream().sorted(Comparator.comparing(ShortlistItem::getId)).toList()) {
      if (item.getStatus() != ShortlistStatus.REMOVED
          || request.getStatus() == ShortlistStatus.SAVED) {
        item.setStatus(request.getStatus());
        shortlistItemRepository.save(item);
      }
    }
    return projection.list(items);
  }

  /** FLOW SL-02: Soft remove khỏi Shortlist đang hoạt động */
  @Override
  @Transactional
  public void removeCreatorFromShortlist(UUID campaignId, UUID shortlistItemId) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(campaignId);
    writeLocks.owned(campaignId);

    ShortlistItem item =
        shortlistItemRepository
            .findLockedByIdAndCampaignId(shortlistItemId, campaignId)
            .filter(this::hasMatchingOwner)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Shortlist item not found"));

    item.setStatus(ShortlistStatus.REMOVED);
    shortlistItemRepository.save(item);
  }

  /** FLOW SL-04: Contact Selection & Readiness Preview */
  @Override
  @Transactional(readOnly = true)
  public ContactReadinessResponse checkContactReadiness(
      UUID campaignId, ContactReadinessRequest request) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    ownership.requireOwned(campaignId);

    List<ShortlistItem> candidates = new ArrayList<>();
    if (request != null && request.getItemIds() != null && !request.getItemIds().isEmpty()) {
      candidates =
          shortlistItemRepository.findByCampaignIdAndIdIn(campaignId, request.getItemIds()).stream()
              .filter(this::hasMatchingOwner)
              .toList();
    } else if (request != null
        && request.getCreatorIds() != null
        && !request.getCreatorIds().isEmpty()) {
      candidates =
          shortlistItemRepository.findByCampaignIdAndCreatorIdIn(
              campaignId, request.getCreatorIds());
    } else {
      candidates =
          shortlistItemRepository.findByCampaignIdAndStatus(
              campaignId, ShortlistStatus.READY_TO_CONTACT);
    }

    var snapshots = projection.latest(candidates);
    int readyCount = 0;
    int manualCount = 0;
    int unavailableCount = 0;
    int alreadyContactedCount = 0;

    List<ContactReadinessResponse.ContactReadinessItem> readinessItems = new ArrayList<>();

    for (ShortlistItem item : candidates) {
      if (!hasMatchingOwner(item)) continue;
      Creator creator = item.getCreator();
      UUID creatorId = creator.getId();
      String creatorName =
          creator.getDisplayName() != null ? creator.getDisplayName() : creator.getUserName();

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
      Optional<PublicCreatorMetric> metricOpt = Optional.ofNullable(snapshots.get(creatorId));
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
                .reason(
                    "Chưa tìm thấy email booking, vui lòng nhắn tin trực tiếp qua trang cá nhân")
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
    return projection.one(item);
  }

  @Override
  public ShortlistOutreachResponse outreachShortlist(
      UUID campaignId, ShortlistOutreachRequest request) {
    return cooperationOutreach.outreach(campaignId, request);
  }

  private boolean hasMatchingOwner(ShortlistItem item) {
    return item.getUser() != null && Permissions.actor().getId().equals(item.getUser().getId());
  }
}
