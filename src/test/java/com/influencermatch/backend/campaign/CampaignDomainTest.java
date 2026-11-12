package com.influencermatch.backend.campaign;

import static org.assertj.core.api.Assertions.*;

import com.influencermatch.backend.campaign.enums.CampaignStatus;
import com.influencermatch.backend.exception.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CampaignDomainTest {

  @Test
  @DisplayName("Draft Campaign - Should be editable")
  void draftIsEditable() {
    assertThatCode(CampaignStatus.DRAFT::requireEditable).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Archived Campaign - Should not be editable and throw ConflictException")
  void archivedIsNotEditable() {
    assertThatThrownBy(CampaignStatus.ARCHIVED::requireEditable)
        .isInstanceOf(ConflictException.class);
  }

  @Test
  @DisplayName("Campaign Transition - Should allow sequential forward workflow")
  void sequentialWorkflowIsAllowed() {
    assertThatCode(() -> CampaignStatus.DRAFT.requireTransitionTo(CampaignStatus.READY_FOR_DISCOVERY))
        .doesNotThrowAnyException();
        
    assertThatCode(() -> CampaignStatus.READY_FOR_DISCOVERY.requireTransitionTo(CampaignStatus.ACTIVE))
        .doesNotThrowAnyException();
        
    assertThatCode(() -> CampaignStatus.ACTIVE.requireTransitionTo(CampaignStatus.COMPLETED))
        .doesNotThrowAnyException();
        
    assertThatCode(() -> CampaignStatus.DRAFT.requireTransitionTo(CampaignStatus.ARCHIVED))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Campaign Transition - Should reject skipped or backward transitions")
  void skippedAndBackwardTransitionsAreRejected() {
    // GIVEN, WHEN & THEN
    assertThatThrownBy(() -> CampaignStatus.DRAFT.requireTransitionTo(CampaignStatus.ACTIVE))
        .isInstanceOf(ConflictException.class);
        
    assertThatThrownBy(() -> CampaignStatus.ACTIVE.requireTransitionTo(CampaignStatus.DRAFT))
        .isInstanceOf(ConflictException.class);
        
    assertThatThrownBy(() -> CampaignStatus.COMPLETED.requireTransitionTo(CampaignStatus.ARCHIVED))
        .isInstanceOf(ConflictException.class);
  }
}
