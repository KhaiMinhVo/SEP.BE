package com.influencermatch.backend.auth.dto;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.auth.service.*;
import com.influencermatch.backend.auth.dto.*;
import com.influencermatch.backend.auth.controller.*;
import com.influencermatch.backend.user.enums.UserStatus; import jakarta.validation.constraints.NotNull;
public record UpdateUserStatusRequest(@NotNull UserStatus status) {}


