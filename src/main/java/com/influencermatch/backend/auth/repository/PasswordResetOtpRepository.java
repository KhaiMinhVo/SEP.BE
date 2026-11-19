package com.influencermatch.backend.auth.repository;

import com.influencermatch.backend.auth.model.PasswordResetOtp;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, UUID> {}
