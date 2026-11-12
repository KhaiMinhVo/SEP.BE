package com.influencermatch.backend.relationship.dto;

import com.influencermatch.backend.relationship.enums.RelationshipStage;
import java.math.BigDecimal;
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
public class RelationshipDto {
  private UUID id;
  private UUID masterId;
  private UUID dealId;
  private String dealStatus;
  private UUID collaborationId;
  private UUID campaignId;
  private UUID creatorId;
  private String creatorName;
  private UUID shortlistItemId;
  private RelationshipStage stage;
  private String contactMethod;
  private BigDecimal offeredFee;
  private BigDecimal counterofferFee;

  @io.swagger.v3.oas.annotations.media.Schema(
      deprecated = true,
      description = "Legacy quote with unknown source; read only")
  private BigDecimal quotedFee;

  private BigDecimal agreedFee;
  private String currency;
  private LocalDateTime lastContactAt;
  private String notes;
  private String nextAction;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
