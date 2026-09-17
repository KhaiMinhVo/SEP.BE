package com.influencermatch.backend.creator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

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
