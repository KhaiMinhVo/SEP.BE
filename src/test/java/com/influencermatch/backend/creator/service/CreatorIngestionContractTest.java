package com.influencermatch.backend.creator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.creator.dto.CreatorIngestionRequest;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.discovery.service.CreatorDiscoveryContract;
import com.influencermatch.backend.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class CreatorIngestionContractTest {
  CreatorRepository creators = mock(CreatorRepository.class);
  PublicCreatorMetricRepository metrics = mock(PublicCreatorMetricRepository.class);
  CreatorIngestionService service = new CreatorIngestionService(creators, metrics,
      mock(PostRepository.class), mock(jakarta.persistence.EntityManager.class));

  CreatorIngestionRequest request() {
    return CreatorIngestionRequest.builder().contractVersion(CreatorDiscoveryContract.DEFINITION.version())
        .engagementRateUnit("PERCENT").engagementRate(new BigDecimal("5"))
        .platform(" tiktok ").externalId("contract").userName("creator").creatorType(" koc ")
        .followers(25000L).category("BEAUTY").niche("Gia dụng").build();
  }
  PublicCreatorMetric existing() {
    var creator = Creator.builder().id(UUID.randomUUID()).platform("TIKTOK").externalId("contract").build();
    var metric = PublicCreatorMetric.builder().id(UUID.randomUUID()).creator(creator).build();
    when(creators.findLockedByPlatformAndExternalId("TIKTOK", "contract")).thenReturn(Optional.of(creator));
    when(creators.save(any())).thenReturn(creator);
    when(metrics.findByCreatorId(creator.getId())).thenReturn(Optional.of(metric));
    when(metrics.save(any())).thenAnswer(i -> i.getArgument(0));
    return metric;
  }

  @Test void ingestionNormalizesUnitsIdentityAndObservedLabelsWithoutMutatingRetryPayload() {
    var metric = existing();
    var req = request();
    service.ingestCreatorData(req);
    service.ingestCreatorData(req);
    assertThat(metric.getEngagementRate()).isEqualByComparingTo("0.05");
    assertThat(req.getEngagementRate()).isEqualByComparingTo("5");
    assertThat(metric.getCreatorType()).isEqualTo("KOC");
    assertThat(metric.getCategory()).isEqualTo("HOME");
    assertThat(metric.getNiche()).isEqualTo("HOME");
  }

  @Test void recrawlClearsStaleClassificationButPreservesAllManagerApprovedLabels() {
    var metric = existing();
    metric.setCategory("FOOD"); metric.setNiche("FOOD");
    service.ingestCreatorData(request().toBuilder().category("UNKNOWN").niche("UNKNOWN").build());
    assertThat(metric.getCategory()).isEqualTo("UNKNOWN");
    assertThat(metric.getNiche()).isEqualTo("UNKNOWN");
    metric.setCategory("FOOD"); metric.setNiche("Ẩm thực miền Tây");
    metric.setQualityReviewedAt(java.time.LocalDateTime.now()); metric.setCreatorType("KOL");
    service.ingestCreatorData(request());
    assertThat(metric.getCategory()).isEqualTo("FOOD");
    assertThat(metric.getNiche()).isEqualTo("Ẩm thực miền Tây");
    assertThat(metric.getCreatorType()).isEqualTo("KOL");
  }

  @Test void existingProfilesStillRefreshAfterFollowersFallBelowTheAdmissionFloor() {
    var metric = existing();
    assertThat(service.ingestCreatorData(request().toBuilder().followers(3000L).build()).getStatus()).isEqualTo("SUCCESS");
    assertThat(metric.getFollowers()).isEqualTo(3000L);
  }

  @Test void legacyCrawlerRatesAreRecomputedFromCountsWithoutGuessingTheirUnits() {
    var metric = existing();
    service.ingestCreatorData(request().toBuilder().contractVersion(null).engagementRateUnit(null)
        .crawlRunId("legacy-run").engagementRate(new BigDecimal("0.5"))
        .avgLikes(new BigDecimal("100")).avgComments(new BigDecimal("20")).avgShares(new BigDecimal("5")).build());
    assertThat(metric.getEngagementRate()).isEqualByComparingTo("0.005");
  }

  @Test void incompatibleContractsAndInvalidValuesAreRejectedBeforeDatabaseWrites() {
    for (var req : List.of(request().toBuilder().contractVersion("future-v2").build(),
        request().toBuilder().engagementRateUnit(null).build(),
        request().toBuilder().engagementRateUnit("ambiguous").build(),
        request().toBuilder().freshnessStatus("not-a-status").build(),
        request().toBuilder().engagementRate(new BigDecimal("-1")).build(),
        request().toBuilder().dataConfidence(new BigDecimal("1.1")).build(),
        request().toBuilder().embedding(List.of(1f, 2f)).build(),
        request().toBuilder().embedding(Collections.nCopies(768, Float.NaN)).build())) {
      assertThatThrownBy(() -> service.ingestCreatorData(req)).isInstanceOf(BadRequestException.class);
    }
    verifyNoInteractions(creators, metrics);
  }
}
