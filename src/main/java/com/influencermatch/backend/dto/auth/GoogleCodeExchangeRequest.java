package com.influencermatch.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record GoogleCodeExchangeRequest(@NotBlank String code) {}
