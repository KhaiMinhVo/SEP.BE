package com.influencermatch.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record GoogleCodeExchangeRequest(@NotBlank String code) {}
