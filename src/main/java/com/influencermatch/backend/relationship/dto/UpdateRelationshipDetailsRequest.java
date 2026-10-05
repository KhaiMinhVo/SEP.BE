package com.influencermatch.backend.relationship.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRelationshipDetailsRequest {
    private String contactMethod;
    private BigDecimal quotedFee;
    private BigDecimal agreedFee;
    private String currency;
    private String notes;
    private String nextAction;
}
