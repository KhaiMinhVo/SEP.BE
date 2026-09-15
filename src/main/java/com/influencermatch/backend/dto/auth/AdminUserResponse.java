package com.influencermatch.backend.dto.auth;
import com.influencermatch.backend.entity.*; import java.time.LocalDateTime; import java.util.UUID;
public record AdminUserResponse(UUID id,String email,String fullName,Role role,UserStatus status,LocalDateTime createdAt,LocalDateTime updatedAt) {
    public static AdminUserResponse from(User u){return new AdminUserResponse(u.getId(),u.getEmail(),u.getFullName(),u.getRole(),u.getStatus(),u.getCreatedAt(),u.getUpdatedAt());}
}
