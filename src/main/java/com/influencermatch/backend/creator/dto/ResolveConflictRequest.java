package com.influencermatch.backend.creator.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResolveConflictRequest {

  @NotNull(message = "Decision is required")
  private ConflictResolutionDecision decision;

  @JsonAlias({"resolutionNotes"})
  private String notes;
}
