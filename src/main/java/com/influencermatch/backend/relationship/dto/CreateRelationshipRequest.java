package com.influencermatch.backend.relationship.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRelationshipRequest {
    @NotNull(message = "Creator ID is required")
    private UUID creatorId;

    private UUID shortlistItemId; // Optional, if this comes from a shortlist

    private String contactMethod;
    private BigDecimal quotedFee;
    private String currency;
    private String notes;
}
