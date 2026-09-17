package com.influencermatch.backend.auth;

import com.influencermatch.backend.auth.dto.LoginRequest;
import com.influencermatch.backend.auth.dto.LoginResponse;
import com.influencermatch.backend.auth.dto.RegisterRequest;
import com.influencermatch.backend.auth.dto.UserProfileResponse;
import com.influencermatch.backend.auth.dto.RefreshTokenRequest;
import com.influencermatch.backend.user.User;

public interface AuthService {

    void register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    LoginResponse refresh(RefreshTokenRequest request);

    void logout(RefreshTokenRequest request);

    UserProfileResponse getProfile(User currentUser);
}


