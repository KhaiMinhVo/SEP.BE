package com.influencermatch.backend.auth.repository;

import com.influencermatch.backend.auth.model.AuthExchangeCode;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface AuthExchangeCodeRepository extends JpaRepository<AuthExchangeCode, UUID> {
    @Query("select c from AuthExchangeCode c where c.codeHash = :codeHash")
    Optional<AuthExchangeCode> findByCodeHash(String codeHash);
}
