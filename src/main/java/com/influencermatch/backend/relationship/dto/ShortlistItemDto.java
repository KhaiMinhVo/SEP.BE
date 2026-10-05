package com.influencermatch.backend.relationship.dto;

import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortlistItemDto {
    private UUID id;
    private UUID campaignId;
    private UUID creatorId;
    private String creatorName; // Helpful for UI
    private ShortlistPriority priority;
    private String note;
    private LocalDateTime addedAt;
}
