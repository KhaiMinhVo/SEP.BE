package com.influencermatch.backend.creator.dto;

import com.influencermatch.backend.creator.controller.*;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.*;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorIngestionResponse {
  private UUID creatorId;
  private UUID metricId;
  private String status;
  private String message;
}
