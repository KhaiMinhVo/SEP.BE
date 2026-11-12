package com.influencermatch.backend.relationship.dto;

import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistSource;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddShortlistItemRequest {
    private UUID creatorId;
    private UUID creatorAccountId;

    private ShortlistPriority priority;
    private String note;
    private ShortlistSource source;
    private UUID recommendationRunId;

    public UUID getEffectiveCreatorId() {
        return creatorId != null ? creatorId : creatorAccountId;
    }
}
