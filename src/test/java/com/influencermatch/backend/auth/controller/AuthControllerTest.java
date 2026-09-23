package com.influencermatch.backend.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.auth.dto.LoginRequest;
import com.influencermatch.backend.auth.dto.LoginResponse;
import com.influencermatch.backend.auth.dto.RegisterRequest;
import com.influencermatch.backend.auth.dto.UserProfileResponse;
import com.influencermatch.backend.auth.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false) // Bypass Spring Security filter chain for pure controller test
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private com.influencermatch.backend.security.JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("POST /auth/register - Success returns 201 Created")
    void register_ValidRequest_Returns201() throws Exception {
        // GIVEN
        RegisterRequest request = createValidRegisterRequest();
        doNothing().when(authService).register(any(RegisterRequest.class));

        // WHEN & THEN
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /auth/register - Missing fields returns 400 Bad Request")
    void register_MissingFields_Returns400() throws Exception {
        // GIVEN
        RegisterRequest request = RegisterRequest.builder()
                .email("invalid-email") // Invalid email format
                // Missing password and fullName
                .build();

        // WHEN & THEN
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /auth/login - Success returns 200 OK and Tokens")
    void login_ValidRequest_Returns200AndToken() throws Exception {
        // GIVEN
        LoginRequest request = createValidLoginRequest();
        LoginResponse mockResponse = createMockLoginResponse();
        when(authService.login(any(LoginRequest.class))).thenReturn(mockResponse);

        // WHEN & THEN
        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("mocked.access.token"))
                .andExpect(jsonPath("$.data.refreshToken").value("mocked.refresh.token"));
    }

    @Test
    @DisplayName("POST /auth/refresh - Success returns 200 OK and New Tokens")
    void refresh_ValidRequest_Returns200AndToken() throws Exception {
        // GIVEN
        String jsonRequest = "{\"refreshToken\": \"valid_refresh_token\"}";
        LoginResponse mockResponse = createMockLoginResponse();
        when(authService.refresh(any())).thenReturn(mockResponse);

        // WHEN & THEN
        mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("mocked.access.token"));
    }

    @Test
    @DisplayName("POST /auth/logout - Success returns 204 No Content")
    void logout_ValidRequest_Returns204() throws Exception {
        // GIVEN
        String jsonRequest = "{\"refreshToken\": \"valid_refresh_token\"}";
        doNothing().when(authService).logout(any());

        // WHEN & THEN
        mockMvc.perform(post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /auth/me - Success returns 200 OK and User Profile")
    void me_ReturnsUserProfile() throws Exception {
        // GIVEN
        UserProfileResponse profileResponse = UserProfileResponse.builder()
                .email("test@example.com")
                .fullName("Test User")
                .build();
        when(authService.getProfile(any())).thenReturn(profileResponse);

        // WHEN & THEN
        mockMvc.perform(get("/auth/me")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("test@example.com"))
                .andExpect(jsonPath("$.data.fullName").value("Test User"));
    }

    private RegisterRequest createValidRegisterRequest() {
        return RegisterRequest.builder()
                .email("test@example.com")
                .password("Password123!")
                .fullName("Test User")
                .build();
    }

    private LoginRequest createValidLoginRequest() {
        return LoginRequest.builder()
                .email("test@example.com")
                .password("Password123!")
                .build();
    }

    private LoginResponse createMockLoginResponse() {
        return LoginResponse.builder()
                .accessToken("mocked.access.token")
                .refreshToken("mocked.refresh.token")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
    }
}
