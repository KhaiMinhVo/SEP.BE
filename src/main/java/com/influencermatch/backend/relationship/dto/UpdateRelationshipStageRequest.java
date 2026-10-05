package com.influencermatch.backend.relationship.dto;

import com.influencermatch.backend.relationship.enums.RelationshipStage;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRelationshipStageRequest {
    @NotNull(message = "Stage is required")
    private RelationshipStage stage;
}
