package com.influencermatch.backend.relationship.dto;

import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactReadinessRequest {
    private List<UUID> itemIds;
    private List<UUID> creatorIds;
}
