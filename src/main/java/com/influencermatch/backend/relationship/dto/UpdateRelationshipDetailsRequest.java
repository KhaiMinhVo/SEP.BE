package com.influencermatch.backend.relationship.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRelationshipDetailsRequest {
  private String contactMethod;
  private BigDecimal offeredFee;
  private BigDecimal counterofferFee;

  @io.swagger.v3.oas.annotations.media.Schema(
      deprecated = true,
      description = "Legacy quote with unknown source; read only")
  private BigDecimal quotedFee;

  private BigDecimal agreedFee;
  private String currency;
  private String notes;
  private String nextAction;
}
