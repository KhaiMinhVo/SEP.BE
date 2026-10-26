package com.influencermatch.backend.relationship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
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
public class ShortlistOutreachRequest {

  @NotEmpty(message = "shortlistItemIds cannot be empty")
  @Schema(
      description = "List of shortlist item IDs to outreach",
      example = "[\"11111111-1111-1111-1111-111111111111\"]")
  private List<UUID> shortlistItemIds;

  @Schema(
      description = "Outreach collaboration message / notes",
      example = "Chào bạn, Brand muốn mời bạn tham gia chiến dịch ra mắt sản phẩm...")
  private String outreachMessage;

  @Schema(description = "Proposed campaign fee", example = "15000000")
  private BigDecimal proposedFee;

  @Builder.Default
  @Schema(description = "Currency code", example = "VND")
  private String currency = "VND";

  @Builder.Default
  @Schema(description = "Contact method", example = "EMAIL_INVITATION")
  private String contactMethod = "EMAIL_INVITATION";
}
