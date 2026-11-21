package com.influencermatch.backend.campaign.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.campaign.repository.CampaignRepository;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

class CampaignDeletionServiceTest {
  final CampaignWriteLock locks = mock(CampaignWriteLock.class);
  final CampaignRepository campaigns = mock(CampaignRepository.class);
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final AuditService audit = mock(AuditService.class);
  final CampaignDeletionService service = new CampaignDeletionService(locks, campaigns, db, audit);
  final UUID id = UUID.randomUUID();
  final Campaign draft = Campaign.builder().id(id).name("Draft").status(CampaignStatus.DRAFT).campaignRevision(2).build();
  final String tag = CampaignRevision.tag(id, 2);

  @BeforeEach void authenticate() { TestActors.authenticate(TestActors.user(Role.BRAND)); }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void deletesOnlyUnusedDraftAndRetainsAudit() {
    when(locks.owned(id)).thenReturn(draft);
    when(db.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
    service.deleteDraft(id, tag);
    var order = inOrder(locks, db, campaigns, audit);
    order.verify(locks).owned(id);
    order.verify(db).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));
    order.verify(db).update("delete from shortlist_item where campaign_id=?", id);
    order.verify(campaigns).delete(draft);
    order.verify(campaigns).flush();
    order.verify(audit).record(eq("DELETE_DRAFT_CAMPAIGN"), eq("CAMPAIGN"), eq(id), any(), isNull(), anyString());
  }

  @ParameterizedTest @EnumSource(value = CampaignStatus.class, names = {"READY_FOR_DISCOVERY", "ACTIVE", "COMPLETED", "ARCHIVED"})
  void neverDeletesOtherStates(CampaignStatus status) {
    draft.setStatus(status); when(locks.owned(id)).thenReturn(draft);
    assertEquals(ErrorCode.CAMPAIGN_DELETE_BLOCKED, assertThrows(BusinessException.class, () -> service.deleteDraft(id, tag)).code());
    verifyNoInteractions(db, campaigns, audit);
  }

  @Test void blocksAnyBusinessHistoryBeforeDeletingShortlist() {
    when(locks.owned(id)).thenReturn(draft);
    when(db.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
    assertEquals(ErrorCode.CAMPAIGN_DELETE_BLOCKED, assertThrows(BusinessException.class, () -> service.deleteDraft(id, tag)).code());
    verify(db, never()).update(anyString(), any(Object[].class));
    verifyNoInteractions(campaigns, audit);
  }

  @Test void requiresCurrentRevision() {
    when(locks.owned(id)).thenReturn(draft);
    assertEquals(ErrorCode.PRECONDITION_REQUIRED, assertThrows(BusinessException.class, () -> service.deleteDraft(id, null)).code());
    assertEquals(ErrorCode.CAMPAIGN_REVISION_CONFLICT, assertThrows(BusinessException.class, () -> service.deleteDraft(id, CampaignRevision.tag(id, 1))).code());
    verifyNoInteractions(db, campaigns, audit);
  }

  @Test void ownershipFailureCannotDeleteData() {
    when(locks.owned(id)).thenThrow(new ForbiddenException("Not your campaign"));
    assertThrows(ForbiddenException.class, () -> service.deleteDraft(id, tag));
    verifyNoInteractions(db, campaigns, audit);
  }

  @Test void foreignKeyFailureIsReportedAndTransactionMustRollBack() {
    when(locks.owned(id)).thenReturn(draft);
    when(db.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
    doThrow(new DataIntegrityViolationException("Concurrent reference")).when(campaigns).flush();
    assertEquals(ErrorCode.CAMPAIGN_DELETE_BLOCKED, assertThrows(BusinessException.class, () -> service.deleteDraft(id, tag)).code());
    verifyNoInteractions(audit);
  }

  @Test void unauthenticatedRequestCannotReachDatabase() {
    SecurityContextHolder.clearContext();
    assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(BusinessException.class, () -> service.deleteDraft(id, tag)).code());
    verifyNoInteractions(locks, db, campaigns, audit);
  }
}
