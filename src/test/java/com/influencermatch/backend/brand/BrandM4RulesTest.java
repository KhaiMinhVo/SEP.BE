package com.influencermatch.backend.brand;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.service.*;
import com.influencermatch.backend.exception.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BrandM4RulesTest {
  @Test
  void revisionRequiresSpecificCurrentEtag() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> BrandRevision.require(null, id, 1))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.PRECONDITION_REQUIRED));
    assertThatThrownBy(() -> BrandRevision.require("*", id, 1))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> BrandRevision.require(BrandRevision.tag(id, 0), id, 1))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.BRAND_REVISION_CONFLICT));
    BrandRevision.require(BrandRevision.tag(id, 1), id, 1);
  }

  @Test
  void semanticContextCanonicalizationExcludesPrivateFields() {
    var mapper = new ObjectMapper();
    var p =
        BrandProfile.builder()
            .businessName("Private company")
            .phoneNumber("+84912345678")
            .productCategories(List.of(" B ", "A", "A"))
            .build();
    var first = BrandContextData.of(p, mapper);
    p.setProductCategories(List.of("A", "B"));
    p.setPhoneNumber("+84999999999");
    assertThat(BrandContextData.of(p, mapper)).isEqualTo(first);
    assertThat(first.toString()).doesNotContain("Private company", "849", "phoneNumber");
    assertThat(first.path("productCategories").get(0).asText()).isEqualTo("A");
  }

  List<BrandLearnedPatterns.Evidence> evidence(
      String platform, String niche, int count, int rating) {
    var result = new ArrayList<BrandLearnedPatterns.Evidence>();
    for (int i = 0; i < count; i++)
      result.add(new BrandLearnedPatterns.Evidence(UUID.randomUUID(), platform, niche, rating));
    return result;
  }

  @Test
  void learnedRequiresFiveDistinctReviewsAndIgnoresUnrelatedPlatform() {
    var rows = evidence("TIKTOK", "Beauty", 4, 5);
    rows.add(rows.getFirst());
    assertThat(BrandLearnedPatterns.aggregate(rows).select("TIKTOK", "Beauty")).isNull();
    rows.addAll(evidence("TIKTOK", "Beauty", 1, 1));
    var snapshot = BrandLearnedPatterns.aggregate(rows);
    var c = snapshot.select(" tiktok ", "beauty");
    assertThat(c.reviewIds()).hasSize(5);
    assertThat(c.confidence()).isEqualTo(0.75);
    assertThat(c.score()).isEqualTo(80);
    assertThat(snapshot.select("INSTAGRAM", "beauty")).isNull();
  }

  @Test
  void cohortPrefersExactAndFallsBackToSamePlatformOnly() {
    var rows = evidence("TIKTOK", "Skin", 5, 5);
    rows.addAll(evidence("TIKTOK", "Food", 5, 1));
    var snap = BrandLearnedPatterns.aggregate(rows);
    assertThat(snap.select("TIKTOK", "Skin").score()).isEqualTo(100);
    assertThat(snap.select("TIKTOK", "Unknown").score()).isEqualTo(50);
    assertThat(snap.select("TIKTOK", "Unknown").reviewIds()).hasSize(10);
    assertThat(snap.select("FACEBOOK", "Skin")).isNull();
  }
}
