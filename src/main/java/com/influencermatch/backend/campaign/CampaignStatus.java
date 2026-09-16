package com.influencermatch.backend.campaign;

import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;

public enum CampaignStatus {
    DRAFT, READY_FOR_DISCOVERY, ACTIVE, COMPLETED, ARCHIVED;
    public void requireEditable() {
        if (this != DRAFT) throw new ConflictException(ErrorCode.CAMPAIGN_NOT_EDITABLE, "Only a draft campaign can be edited");
    }
}
