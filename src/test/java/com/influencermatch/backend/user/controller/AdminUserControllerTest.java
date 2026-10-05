package com.influencermatch.backend.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.auth.dto.AdminUserResponse;
import com.influencermatch.backend.auth.dto.UpdateUserStatusRequest;
import com.influencermatch.backend.user.dto.UpdateUserRoleRequest;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.enums.UserStatus;
import com.influencermatch.backend.user.service.AdminUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserController.class)
@AutoConfigureMockMvc(addFilters = false) // Bypass Spring Security filter chain for pure controller test
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AdminUserService adminUserService;

    @MockBean
    private com.influencermatch.backend.security.JwtTokenProvider jwtTokenProvider;

    @MockBean
    private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

    @Test
    @DisplayName("GET /admin/users - Success")
    void list_Success() throws Exception {
        UUID userId = UUID.randomUUID();
        AdminUserResponse userResponse = new AdminUserResponse(
                userId, "test@test.com", "Test User", Role.DATA_MANAGER, UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now()
        );

        Page<AdminUserResponse> page = new PageImpl<>(List.of(userResponse));
        when(adminUserService.list(any(PageRequest.class))).thenReturn(page);

        mockMvc.perform(get("/admin/users")
                .param("page", "0")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].email").value("test@test.com"));
    }

    @Test
    @DisplayName("GET /admin/users/{id} - Success")
    void get_Success() throws Exception {
        UUID userId = UUID.randomUUID();
        AdminUserResponse userResponse = new AdminUserResponse(
                userId, "test@test.com", "Test User", Role.DATA_MANAGER, UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now()
        );

        when(adminUserService.get(userId)).thenReturn(userResponse);

        mockMvc.perform(get("/admin/users/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("test@test.com"));
    }

    @Test
    @DisplayName("PATCH /admin/users/{id}/role - Success")
    void updateRole_Success() throws Exception {
        UUID userId = UUID.randomUUID();
        UpdateUserRoleRequest request = new UpdateUserRoleRequest(Role.ADMIN, "Promote to Admin");
        
        AdminUserResponse userResponse = new AdminUserResponse(
                userId, "test@test.com", "Test User", Role.ADMIN, UserStatus.ACTIVE, LocalDateTime.now(), LocalDateTime.now()
        );

        when(adminUserService.changeRole(eq(userId), any(UpdateUserRoleRequest.class))).thenReturn(userResponse);

        mockMvc.perform(patch("/admin/users/{id}/role", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    @DisplayName("PATCH /admin/users/{id}/status - Success")
    void updateStatus_Success() throws Exception {
        UUID userId = UUID.randomUUID();
        UpdateUserStatusRequest request = new UpdateUserStatusRequest(UserStatus.LOCKED, "Violated policy");

        AdminUserResponse userResponse = new AdminUserResponse(
                userId, "test@test.com", "Test User", Role.DATA_MANAGER, UserStatus.LOCKED, LocalDateTime.now(), LocalDateTime.now()
        );

        when(adminUserService.changeStatus(eq(userId), any(UpdateUserStatusRequest.class))).thenReturn(userResponse);

        mockMvc.perform(patch("/admin/users/{id}/status", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("LOCKED"));
    }
}
