package com.influencermatch.backend.taxonomy.dto;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NicheRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 500) String description,
    @NotNull Boolean active,
    @Size(max = 30) List<@NotBlank @Size(max = 100) String> keywords) {
  public NicheRequest(String name, String description, Boolean active) {
    this(name, description, active, null);
  }
}
