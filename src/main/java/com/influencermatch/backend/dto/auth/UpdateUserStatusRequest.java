package com.influencermatch.backend.dto.auth;
import com.influencermatch.backend.entity.UserStatus; import jakarta.validation.constraints.NotNull;
public record UpdateUserStatusRequest(@NotNull UserStatus status) {}
