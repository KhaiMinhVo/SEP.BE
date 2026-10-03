package com.influencermatch.backend.billing.repository;

import com.influencermatch.backend.billing.controller.*;
import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.service.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
  Optional<Payment> findByTransactionCode(String transactionCode);

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query(
      "select p from Payment p where p.transactionCode = :code")
  java.util.Optional<Payment> findLockedByTransactionCode(
      @org.springframework.data.repository.query.Param("code") String code);
}
