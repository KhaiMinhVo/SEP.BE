package com.influencermatch.backend.support;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.model.BrandContext;
import com.influencermatch.backend.brand.repository.BrandContextRepository;
import com.influencermatch.backend.brand.service.BrandLearnedPatterns;
import java.util.*;
import org.springframework.test.util.ReflectionTestUtils;

public final class TestM4Fixtures {
  public static void wire(Object service) {
    var contexts = mock(BrandContextRepository.class);
    lenient()
        .when(contexts.findByBrandProfileId(any()))
        .thenAnswer(
            i ->
                Optional.of(
                    BrandContext.builder()
                        .id(UUID.randomUUID())
                        .contextVersion(1)
                        .profileContext(new ObjectMapper().createObjectNode())
                        .build()));
    ReflectionTestUtils.setField(service, "brandContexts", contexts);
    if (org.springframework.util.ReflectionUtils.findField(service.getClass(), "m3") != null
        && service
            instanceof com.influencermatch.backend.recommendation.service.AiRecommendationService) {
      var m3 = mock(com.influencermatch.backend.campaign.service.CampaignM3ContextService.class);
      var store = mock(com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore.class);
      var repo =
          (com.influencermatch.backend.campaign.repository.CampaignContextRepository)
              ReflectionTestUtils.getField(service, "campaignContextRepository");
      lenient()
          .when(m3.prepare(any()))
          .thenAnswer(
              i -> {
                var rows = repo.findByCampaignIdOrderByContextVersionDesc(i.getArgument(0));
                var c =
                    rows.isEmpty()
                        ? com.influencermatch.backend.campaign.model.CampaignContext.builder()
                            .id(UUID.randomUUID())
                            .contextVersion(1)
                            .contextData(new ObjectMapper().createObjectNode())
                            .build()
                        : rows.getFirst();
                if (c.getId() == null) c.setId(UUID.randomUUID());
                if (c.getEmbeddingSourceContextId() == null)
                  c.setEmbeddingSourceContextId(c.getId());
                return c;
              });
      lenient()
          .when(store.ready(any()))
          .thenAnswer(
              i ->
                  new com.influencermatch.backend.campaign.embedding.CampaignEmbeddingStore.Ready(
                      UUID.randomUUID(),
                      i.getArgument(0),
                      "bkai-foundation-models/vietnamese-bi-encoder",
                      "m3-embed-v1",
                      "Fixture brief",
                      "[" + String.join(",", Collections.nCopies(768, "0.1")) + "]"));
      var locks = mock(com.influencermatch.backend.campaign.service.CampaignWriteLock.class);
      var ownership =
          (com.influencermatch.backend.relationship.service.CampaignOwnershipService)
              ReflectionTestUtils.getField(service, "ownership");
      lenient().when(locks.owned(any())).thenAnswer(i -> ownership.requireOwned(i.getArgument(0)));
      ReflectionTestUtils.setField(service, "m3", m3);
      ReflectionTestUtils.setField(service, "m3Embeddings", store);
      ReflectionTestUtils.setField(service, "campaignLocks", locks);
    }
    if (org.springframework.util.ReflectionUtils.findField(service.getClass(), "learned") != null) {
      var learned = mock(BrandLearnedPatterns.class);
      lenient()
          .when(learned.snapshot(any()))
          .thenReturn(new BrandLearnedPatterns.Snapshot(List.of()));
      ReflectionTestUtils.setField(service, "learned", learned);
    }
  }
}
