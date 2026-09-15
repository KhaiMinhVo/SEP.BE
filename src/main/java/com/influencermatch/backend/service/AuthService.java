package com.influencermatch.backend.service;

import com.influencermatch.backend.dto.auth.LoginRequest;
import com.influencermatch.backend.dto.auth.LoginResponse;
import com.influencermatch.backend.dto.auth.RegisterRequest;
import com.influencermatch.backend.dto.auth.UserProfileResponse;
import com.influencermatch.backend.dto.auth.RefreshTokenRequest;
import com.influencermatch.backend.entity.User;

public interface AuthService {

    void register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    LoginResponse refresh(RefreshTokenRequest request);

    void logout(RefreshTokenRequest request);

    UserProfileResponse getProfile(User currentUser);
}
