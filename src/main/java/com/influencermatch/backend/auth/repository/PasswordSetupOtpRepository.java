package com.influencermatch.backend.auth.repository;
import com.influencermatch.backend.auth.model.PasswordSetupOtp;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PasswordSetupOtpRepository extends JpaRepository<PasswordSetupOtp,UUID> {}
