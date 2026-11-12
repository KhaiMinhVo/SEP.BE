package com.influencermatch.backend.relationship.dto;

import com.influencermatch.backend.relationship.enums.ShortlistStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
public class BatchUpdateStatusRequest {
    @NotEmpty(message = "itemIds must not be empty")
    private List<UUID> itemIds;

    @NotNull(message = "status is required")
    private ShortlistStatus status;
}
