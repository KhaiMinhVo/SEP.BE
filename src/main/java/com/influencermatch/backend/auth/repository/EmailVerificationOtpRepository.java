package com.influencermatch.backend.auth.repository;
import com.influencermatch.backend.auth.model.EmailVerificationOtp;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EmailVerificationOtpRepository extends JpaRepository<EmailVerificationOtp,UUID> {}
