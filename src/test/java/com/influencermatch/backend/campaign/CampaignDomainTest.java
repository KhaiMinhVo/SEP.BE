package com.influencermatch.backend.campaign;

import com.influencermatch.backend.exception.ConflictException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CampaignDomainTest {
    @Test void draftIsEditable() { assertThatCode(CampaignStatus.DRAFT::requireEditable).doesNotThrowAnyException(); }
    @Test void archivedIsNotEditable() { assertThatThrownBy(CampaignStatus.ARCHIVED::requireEditable).isInstanceOf(ConflictException.class); }
    @Test void sequentialWorkflowIsAllowed() {
        assertThatCode(() -> CampaignStatus.DRAFT.requireTransitionTo(CampaignStatus.READY_FOR_DISCOVERY)).doesNotThrowAnyException();
        assertThatCode(() -> CampaignStatus.READY_FOR_DISCOVERY.requireTransitionTo(CampaignStatus.ACTIVE)).doesNotThrowAnyException();
        assertThatCode(() -> CampaignStatus.ACTIVE.requireTransitionTo(CampaignStatus.COMPLETED)).doesNotThrowAnyException();
        assertThatCode(() -> CampaignStatus.DRAFT.requireTransitionTo(CampaignStatus.ARCHIVED)).doesNotThrowAnyException();
    }
    @Test void skippedAndBackwardTransitionsAreRejected() {
        assertThatThrownBy(() -> CampaignStatus.DRAFT.requireTransitionTo(CampaignStatus.ACTIVE)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> CampaignStatus.ACTIVE.requireTransitionTo(CampaignStatus.DRAFT)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> CampaignStatus.COMPLETED.requireTransitionTo(CampaignStatus.ARCHIVED)).isInstanceOf(ConflictException.class);
    }
}


