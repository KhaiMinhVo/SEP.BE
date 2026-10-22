package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.creator.dto.DataQualityDtos.*;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import jakarta.persistence.EntityManager;
import jakarta.validation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('REVIEW_CREATOR_CLASSIFICATION')")
public class DataQualityService {
  private final DataQualityRepository metrics;
  private final CreatorRepository creators;
  private final EntityManager em;
  private final AuditService audit;
  private final Validator validator;

  @Transactional(readOnly = true)
  public PageResponse<QualityItem> pending(int page, int size) {
    Permissions.require(Permission.REVIEW_CREATOR_CLASSIFICATION);
    return PageResponse.from(metrics.pending(PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size))))
        .map(this::item));
  }

  @Transactional
  public QualityItem approve(UUID id, ApproveRequest request) {
    Permissions.require(Permission.REVIEW_CREATOR_CLASSIFICATION);
    var violations = validator.validate(request);
    if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
    UUID creatorId = metrics.creatorId(id).orElseThrow(() -> new NotFoundException("Metric not found"));
    // Same lock order as ingestion: creator first, then its metric snapshot.
    creators.findLockedById(creatorId).orElseThrow(() -> new NotFoundException("Creator not found"));
    PublicCreatorMetric metric = metrics.findLocked(id).orElseThrow(() -> new NotFoundException("Metric not found"));
    em.refresh(metric);
    var latest = metrics.findFirstByCreatorIdOrderByCollectedAtDescIdDesc(creatorId);
    if (latest.isEmpty() || !latest.get().getId().equals(id) || !"KOC".equals(metric.getCreatorType())
        || metric.getDataConfidence() == null || metric.getDataConfidence().compareTo(new BigDecimal("0.7")) >= 0
        || metric.getQualityReviewedAt() != null) {
      throw new ConflictException(ErrorCode.DATA_QUALITY_REVIEW_CONFLICT, "Snapshot is no longer pending review");
    }
    QualityItem before = item(metric);
    if (request.niche() != null) metric.setNiche(request.niche());
    metric.setQualityReviewedBy(Permissions.actor());
    metric.setQualityReviewedAt(LocalDateTime.now());
    metrics.flush();
    QualityItem after = item(metric);
    audit.record("APPROVE_DATA_QUALITY", "PublicCreatorMetric", id, before, after, "Human reviewed KOC classification");
    return after;
  }

  private QualityItem item(PublicCreatorMetric metric) {
    var c = metric.getCreator();
    return new QualityItem(metric.getId(), c.getId(), c.getPlatform(), c.getUserName(), c.getDisplayName(),
        c.getProfileUrl(), metric.getNiche(), metric.getContentSummary(), metric.getDataConfidence(),
        metric.getCollectedAt(), metric.getQualityReviewedBy() == null ? null : metric.getQualityReviewedBy().getId(),
        metric.getQualityReviewedAt());
  }
}
