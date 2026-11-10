package com.influencermatch.backend.auth.dto;

import jakarta.validation.constraints.*;

public record PolicyAcceptance(
    @NotNull @AssertTrue Boolean acceptedTerms,
    @NotBlank @Size(max = 100) String termsVersion,
    @NotNull @AssertTrue Boolean acceptedPrivacy,
    @NotBlank @Size(max = 100) String privacyVersion) {}
