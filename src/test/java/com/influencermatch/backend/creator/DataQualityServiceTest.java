package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.creator.dto.DataQualityDtos.ApproveRequest;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.DataQualityService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import jakarta.persistence.EntityManager;
import jakarta.validation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.data.domain.*;
import org.springframework.security.core.context.SecurityContextHolder;

class DataQualityServiceTest {
  final DataQualityRepository metrics=mock(DataQualityRepository.class);
  final CreatorRepository creators=mock(CreatorRepository.class);
  final EntityManager em=mock(EntityManager.class);
  final AuditService audit=mock(AuditService.class);
  final ValidatorFactory factory=Validation.buildDefaultValidatorFactory();
  final DataQualityService service=new DataQualityService(metrics,creators,em,audit,factory.getValidator());
  final com.influencermatch.backend.user.model.User reviewer=TestActors.user(Role.DATA_MANAGER);
  @BeforeEach void login(){TestActors.authenticate(reviewer);}
  @AfterEach void clear(){SecurityContextHolder.clearContext();factory.close();}
  PublicCreatorMetric pending(){
    var c=Creator.builder().id(UUID.randomUUID()).platform("TIKTOK").userName("koc").build();
    var m=PublicCreatorMetric.builder().id(UUID.randomUUID()).creator(c).creatorType("KOC")
        .niche("Old").dataConfidence(new BigDecimal("0.65")).collectedAt(LocalDateTime.of(2026,1,1,0,0)).build();
    when(metrics.creatorId(m.getId())).thenReturn(Optional.of(c.getId()));
    when(creators.findLockedById(c.getId())).thenReturn(Optional.of(c));
    when(metrics.findLocked(m.getId())).thenReturn(Optional.of(m));
    when(metrics.findFirstByCreatorIdOrderByCollectedAtDescIdDesc(c.getId())).thenReturn(Optional.of(m));return m;
  }
  @Test void approvalKeepsConfidenceAndLocksCreatorBeforeSnapshot(){
    var m=pending();var result=service.approve(m.getId(),new ApproveRequest(" Beauty "));
    assertThat(result.niche()).isEqualTo("Beauty");assertThat(result.dataConfidence()).isEqualByComparingTo("0.65");
    assertThat(result.qualityReviewedBy()).isEqualTo(reviewer.getId());assertThat(result.qualityReviewedAt()).isNotNull();
    InOrder order=inOrder(creators,metrics,em,audit);
    order.verify(metrics).creatorId(m.getId());order.verify(creators).findLockedById(m.getCreator().getId());
    order.verify(metrics).findLocked(m.getId());order.verify(em).refresh(m);
    order.verify(metrics).findFirstByCreatorIdOrderByCollectedAtDescIdDesc(m.getCreator().getId());
    order.verify(metrics).flush();order.verify(audit).record(eq("APPROVE_DATA_QUALITY"),anyString(),eq(m.getId()),any(),any(),anyString());
  }
  @ParameterizedTest @ValueSource(strings={"kol","nullConfidence","threshold","reviewed","stale"})
  void ineligibleSnapshotDoesNotChangeOrAudit(String kind){
    var m=pending();
    switch(kind){case "kol"->m.setCreatorType("KOL");case "nullConfidence"->m.setDataConfidence(null);
      case "threshold"->m.setDataConfidence(new BigDecimal("0.7"));case "reviewed"->m.setQualityReviewedAt(LocalDateTime.of(2026,1,2,0,0));
      case "stale"->when(metrics.findFirstByCreatorIdOrderByCollectedAtDescIdDesc(m.getCreator().getId()))
          .thenReturn(Optional.of(PublicCreatorMetric.builder().id(UUID.randomUUID()).build()));}
    var reviewedAt=m.getQualityReviewedAt();var confidence=m.getDataConfidence();
    assertThatThrownBy(()->service.approve(m.getId(),new ApproveRequest("New"))).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.code()).isEqualTo(ErrorCode.DATA_QUALITY_REVIEW_CONFLICT));
    assertThat(m.getNiche()).isEqualTo("Old");assertThat(m.getQualityReviewedAt()).isEqualTo(reviewedAt);
    assertThat(m.getDataConfidence()).isEqualTo(confidence);verify(metrics,never()).flush();verifyNoInteractions(audit);
  }
  @Test void invalidNicheFailsBeforeLookupAndMissingMetricIs404(){
    assertThatThrownBy(()->service.approve(UUID.randomUUID(),new ApproveRequest(" "))).isInstanceOf(ConstraintViolationException.class);
    verifyNoInteractions(metrics,creators,em,audit);
    assertThatThrownBy(()->service.approve(UUID.randomUUID(),new ApproveRequest(null))).isInstanceOf(NotFoundException.class);
    verifyNoInteractions(creators,em,audit);
  }
  @Test void pendingClampsPageAndSize(){
    when(metrics.pending(any())).thenReturn(Page.empty());service.pending(-1,1000);
    verify(metrics).pending(PageRequest.of(0,100));
  }
}
