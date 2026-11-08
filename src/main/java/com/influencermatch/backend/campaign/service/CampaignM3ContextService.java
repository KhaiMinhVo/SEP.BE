package com.influencermatch.backend.campaign.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.brand.repository.BrandContextRepository;
import com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.*;
import com.influencermatch.backend.campaign.repository.*;
import com.influencermatch.backend.exception.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class CampaignM3ContextService {
  private final CampaignContextRepository contexts;
  private final BrandContextRepository brands;
  private final CampaignWriteLock locks;
  private final CampaignEmbeddingStore embeddings;
  private final ObjectMapper json;
  private final java.time.Clock clock;

  public CampaignContext latest(UUID id) {
    return contexts.findByCampaignIdOrderByContextVersionDesc(id).stream().findFirst().orElse(null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public CampaignContext create(Campaign c, boolean legacy) {
    var old = latest(c.getId());
    var brand = brands.findByBrandProfileId(c.getBrandProfile().getId()).orElse(null);
    var data = (ObjectNode) CampaignBriefData.snapshot(c, json);
    if (old != null) {
      for (String f : List.of("excludedCreatorIds", "feedbackHistory"))
        if (old.getContextData().has(f)) data.set(f, old.getContextData().get(f).deepCopy());
    }
    data.put("snapshotKind", legacy ? "LEGACY_SNAPSHOT" : "BRIEF_SNAPSHOT");
    UUID brandId =
        old != null && old.getBrandContextId() != null
            ? old.getBrandContextId()
            : brand == null ? null : brand.getId();
    var frozenBrand =
        brandId == null
            ? json.createObjectNode()
            : brands
                .findById(brandId)
                .map(b -> b.getProfileContext())
                .orElse(json.createObjectNode());
    data.put("embeddingText", CampaignBriefData.text(data, frozenBrand));
    archive(old);
    var next =
        contexts.saveAndFlush(
            CampaignContext.builder()
                .campaign(c)
                .contextVersion(old == null ? 1 : old.getContextVersion() + 1)
                .brandContextId(brandId)
                .contextData(data)
                .active(true)
                .build());
    embeddings.enqueue(next.getId());
    return next;
  }

  public void archive(CampaignContext old) {
    if (old != null && old.isActive()) {
      old.setActive(false);
      old.setArchivedAt(
          java.time.LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
      contexts.saveAndFlush(old);
    }
  }

  // Separate short transaction deliberately commits compatibility preparation before the caller
  // returns 409.
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CampaignContext prepare(UUID id) {
    var c = locks.owned(id);
    if (c.getStatus() == CampaignStatus.DRAFT || c.getStatus() == CampaignStatus.ARCHIVED)
      throw new ConflictException(
          ErrorCode.CAMPAIGN_CONTEXT_NOT_READY,
          "Campaign must be READY_FOR_DISCOVERY, ACTIVE or COMPLETED");
    var current = latest(id);
    if (current == null || current.getEmbeddingSourceContextId() == null) current = create(c, true);
    else embeddings.enqueue(current.getEmbeddingSourceContextId());
    return current;
  }
}
