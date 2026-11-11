package com.influencermatch.backend.relationship.dto;

import com.influencermatch.backend.relationship.enums.ShortlistPriority;
import com.influencermatch.backend.relationship.enums.ShortlistSource;
import jakarta.validation.constraints.NotEmpty;
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
public class BatchAddShortlistItemRequest {
  @NotEmpty(message = "creatorIds must not be empty")
  private List<UUID> creatorIds;

  private ShortlistPriority priority;
  private String note;
  private ShortlistSource source;
  private UUID recommendationRunId;
}

