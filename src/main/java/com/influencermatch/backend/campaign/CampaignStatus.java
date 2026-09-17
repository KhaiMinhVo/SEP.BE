package com.influencermatch.backend.campaign;

import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;

public enum CampaignStatus {
    DRAFT, READY_FOR_DISCOVERY, ACTIVE, COMPLETED, ARCHIVED;

    public void requireEditable() {
        if (this != DRAFT) throw new ConflictException(ErrorCode.CAMPAIGN_NOT_EDITABLE, "Only a draft campaign can be edited");
    }

    public void requireTransitionTo(CampaignStatus target) {
        boolean allowed = switch (this) {
            case DRAFT -> target == READY_FOR_DISCOVERY || target == ARCHIVED;
            case READY_FOR_DISCOVERY -> target == ACTIVE;
            case ACTIVE -> target == COMPLETED;
            case COMPLETED, ARCHIVED -> false;
        };
        if (!allowed) {
            throw new ConflictException(ErrorCode.INVALID_CAMPAIGN_TRANSITION,
                    "Campaign cannot transition from " + this + " to " + target);
        }
    }
}


