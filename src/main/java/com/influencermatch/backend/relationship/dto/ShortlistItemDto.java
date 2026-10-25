package com.influencermatch.backend.relationship.dto;

import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistSource;
import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortlistItemDto {
    private UUID id;
    private UUID campaignId;
    private UUID creatorId;
    private UUID masterId;
    private String creatorName;
    private ShortlistPriority priority;
    private ShortlistStatus status;
    private ShortlistSource source;
    private UUID recommendationRunId;
    private String note;
    private LocalDateTime addedAt;

    // Idempotent Shortlist Cart Response attributes
    private String action; // "ADDED" or "ALREADY_EXISTS"
    private Boolean isInShortlist;
    private Long totalShortlistCount;
    private String message;
}
