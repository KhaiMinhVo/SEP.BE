package com.influencermatch.backend.auth.dto;
import com.influencermatch.backend.user.UserStatus; import jakarta.validation.constraints.NotNull;
public record UpdateUserStatusRequest(@NotNull UserStatus status) {}


