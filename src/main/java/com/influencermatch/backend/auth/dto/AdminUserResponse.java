package com.influencermatch.backend.auth.dto;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.auth.service.*;
import com.influencermatch.backend.auth.dto.*;
import com.influencermatch.backend.auth.controller.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus; import java.time.LocalDateTime; import java.util.UUID;
public record AdminUserResponse(UUID id,String email,String fullName,Role role,UserStatus status,LocalDateTime createdAt,LocalDateTime updatedAt) {
    public static AdminUserResponse from(User u){return new AdminUserResponse(u.getId(),u.getEmail(),u.getFullName(),u.getRole(),u.getStatus(),u.getCreatedAt(),u.getUpdatedAt());}
}


