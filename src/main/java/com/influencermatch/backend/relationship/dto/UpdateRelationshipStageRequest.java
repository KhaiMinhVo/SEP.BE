package com.influencermatch.backend.relationship.dto;

import com.influencermatch.backend.relationship.enums.RelationshipStage;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateRelationshipStageRequest {
    @NotNull(message = "Stage is required")
    private RelationshipStage stage;

    private BigDecimal agreedFee;

    private String nextAction;
}
