package com.influencermatch.backend.discovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.brand.model.BrandProfile;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.creator.repository.PublicCreatorMetricRepository;
import com.influencermatch.backend.discovery.controller.CreatorDiscoveryController;
import com.influencermatch.backend.discovery.dto.*;
import com.influencermatch.backend.discovery.service.CreatorDiscoveryService;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.*;
import org.springframework.security.core.context.SecurityContextHolder;

class DiscoveryRegressionTest {
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void sparseResultsKeepExplicitBoundsAndPaginationStable() {
    var user = TestActors.user(Role.BRAND);
    TestActors.authenticate(user);
    var campaign = Campaign.builder().id(UUID.randomUUID())
        .brandProfile(BrandProfile.builder().user(user).build())
        .status(CampaignStatus.DRAFT).platforms(List.of("TIKTOK"))
        .niches(List.of("BEAUTY")).followerMin(10000L).followerMax(100000L).build();
    var campaigns = mock(CampaignRepository.class);
    when(campaigns.findById(campaign.getId())).thenReturn(Optional.of(campaign));
    var metrics = mock(PublicCreatorMetricRepository.class);
    var candidate = mock(HybridDiscoveryCreatorProjection.class);
    when(metrics.searchDiscoveryCreators(anyBoolean(), anyList(), anyBoolean(), anyList(),
        any(), any(), anyString(), anyString(), any(), any(), anyBoolean(), anyList(), anyList(), anyList(), any()))
        .thenReturn(new PageImpl<>(List.of(candidate)));
    var service = new CreatorDiscoveryService(campaigns, metrics, mock(com.influencermatch.backend.creator.client.AiServiceClient.class));
    var req = DiscoverySearchRequest.builder().minFollowers(20000L).maxFollowers(50000L)
        .size(1).build();
    assertThat(service.discoverCreators(campaign.getId(), req).getContent()).containsExactly(candidate);
    assertThat(req.getMinFollowers()).isEqualTo(20000L);
    assertThat(req.getMaxFollowers()).isEqualTo(50000L);
    req.setPage(1);
    var next = service.discoverCreators(campaign.getId(), req);
    assertThat(next.getContent()).isEmpty();
    assertThat(next.getTotalElements()).isEqualTo(1);
    verify(metrics, times(2)).searchDiscoveryCreators(eq(true), eq(List.of("TIKTOK")),
        eq(true), anyList(), eq(20000L), eq(50000L), anyString(), eq(""), isNull(),
        any(), eq(false), anyList(), anyList(), anyList(), eq(PageRequest.of(0, 50)));
  }

  @Test void discoveryDoesNotExposeAiScoresBeforeShortlisting() {
    var service = mock(CreatorDiscoveryService.class);
    var candidate = mock(HybridDiscoveryCreatorProjection.class);
    when(candidate.getHybridScore()).thenReturn(0.0);
    when(candidate.getEngagementRate()).thenReturn(new BigDecimal("9.5"));
    when(service.discoverCreators(any(), any())).thenReturn(new PageImpl<>(List.of(candidate)));
    var response = new CreatorDiscoveryController(service)
        .discoverCreators(UUID.randomUUID(), new DiscoverySearchRequest());
    var card = response.getBody().data().getContent().getFirst();
    assertThat(card).containsEntry("aiScoreAvailable", false)
        .containsEntry("matchScore", null).containsEntry("score", null).containsEntry("finalScore", null);
  }

  @Test void invalidQueryVectorsFallBackWithoutRelaxingCampaignFilters() {
    var user = TestActors.user(Role.BRAND);
    TestActors.authenticate(user);
    var campaign = Campaign.builder().id(UUID.randomUUID())
        .brandProfile(BrandProfile.builder().user(user).build()).status(CampaignStatus.DRAFT)
        .productService("Sunscreen").platforms(List.of("TIKTOK")).niches(List.of("BEAUTY"))
        .followerMin(10000L).followerMax(100000L).build();
    var campaigns = mock(CampaignRepository.class);
    when(campaigns.findById(campaign.getId())).thenReturn(Optional.of(campaign));
    var metrics = mock(PublicCreatorMetricRepository.class);
    when(metrics.searchDiscoveryCreators(anyBoolean(), anyList(), anyBoolean(), anyList(),
        any(), any(), anyString(), anyString(), any(), any(), anyBoolean(), anyList(), anyList(), anyList(), any()))
        .thenReturn(Page.empty());
    var ai = mock(com.influencermatch.backend.creator.client.AiServiceClient.class);
    var service = new CreatorDiscoveryService(campaigns, metrics, ai);
    List<List<Float>> invalid = Arrays.asList(null, List.of(), List.of(1.0f, 2.0f),
        Collections.nCopies(768, 0.0f), Collections.nCopies(768, Float.NaN),
        Collections.nCopies(768, Float.POSITIVE_INFINITY));
    for (var vector : invalid) {
      when(ai.getEmbedding(anyString())).thenReturn(vector);
      assertThat(service.discoverCreators(campaign.getId(), new DiscoverySearchRequest()).isEmpty()).isTrue();
    }
    verify(metrics, times(invalid.size())).searchDiscoveryCreators(eq(true), eq(List.of("TIKTOK")),
        eq(true), anyList(), eq(10000L), eq(100000L), anyString(), anyString(), isNull(),
        any(), eq(false), anyList(), anyList(), anyList(), eq(PageRequest.of(0, 50)));
    verify(ai, never()).getExplanation(any());
  }
}
