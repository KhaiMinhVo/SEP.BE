package com.influencermatch.backend.campaign;

import com.influencermatch.backend.exception.ConflictException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CampaignDomainTest {
    @Test void draftIsEditable() { assertThatCode(CampaignStatus.DRAFT::requireEditable).doesNotThrowAnyException(); }
    @Test void archivedIsNotEditable() { assertThatThrownBy(CampaignStatus.ARCHIVED::requireEditable).isInstanceOf(ConflictException.class); }
}
