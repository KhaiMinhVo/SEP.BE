package com.influencermatch.backend.relationship.dto;

import com.influencermatch.backend.relationship.enums.*;
import jakarta.validation.constraints.*;
import java.util.*;

public record BatchAddShortlistRequest(
    @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> creatorIds,
    ShortlistPriority priority,
    String note,
    ShortlistSource source,
    UUID recommendationRunId) {}
