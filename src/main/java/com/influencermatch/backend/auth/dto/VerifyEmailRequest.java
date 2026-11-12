package com.influencermatch.backend.auth.dto;
import jakarta.validation.constraints.*;
public record VerifyEmailRequest(@NotBlank @Email String email,@NotNull @Pattern(regexp="[0-9]{6}") String otp) {}
