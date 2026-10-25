package com.influencermatch.backend.relationship.dto;

import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistSource;
import jakarta.validation.constraints.NotNull;
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
    @NotNull(message = "Creator ID is required")
    private UUID creatorId;

    private ShortlistPriority priority;

    private String note;

    private ShortlistSource source;

    private UUID recommendationRunId;
}
