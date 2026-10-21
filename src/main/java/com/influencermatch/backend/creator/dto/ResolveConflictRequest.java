package com.influencermatch.backend.creator.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveConflictRequest {

  @NotNull(message = "Decision is required")
  private ConflictResolutionDecision decision;

  private String notes;
}
