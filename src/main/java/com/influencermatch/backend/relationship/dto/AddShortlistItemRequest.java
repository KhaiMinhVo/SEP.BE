package com.influencermatch.backend.relationship.dto;

import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddShortlistItemRequest {
    @NotNull(message = "Creator ID is required")
    private UUID creatorId;

    @NotNull(message = "Priority is required")
    private ShortlistPriority priority;

    private String note;
}
