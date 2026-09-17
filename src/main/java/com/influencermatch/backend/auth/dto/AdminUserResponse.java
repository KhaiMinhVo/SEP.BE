package com.influencermatch.backend.auth.dto;
import com.influencermatch.backend.user.User;
import com.influencermatch.backend.user.Role;
import com.influencermatch.backend.user.UserStatus; import java.time.LocalDateTime; import java.util.UUID;
public record AdminUserResponse(UUID id,String email,String fullName,Role role,UserStatus status,LocalDateTime createdAt,LocalDateTime updatedAt) {
    public static AdminUserResponse from(User u){return new AdminUserResponse(u.getId(),u.getEmail(),u.getFullName(),u.getRole(),u.getStatus(),u.getCreatedAt(),u.getUpdatedAt());}
}


