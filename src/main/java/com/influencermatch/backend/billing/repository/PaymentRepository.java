package com.influencermatch.backend.billing.repository;

import com.influencermatch.backend.billing.enums.PaymentStatus;
import com.influencermatch.backend.billing.model.Payment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository
    extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

  interface PlanRevenueProjection {
    UUID getPlanId();

    String getPlanName();

    String getCurrency();

    long getPaymentCount();

    BigDecimal getRevenue();
  }

  Optional<Payment> findByTransactionCode(String transactionCode);

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payment p where p.transactionCode = :code")
  Optional<Payment> findLockedByTransactionCode(@Param("code") String code);

  @EntityGraph(attributePaths = {"brandProfile", "brandProfile.user", "subscription", "subscription.plan"})
  Page<Payment> findAll(Specification<Payment> spec, Pageable pageable);

  long countByStatus(PaymentStatus status);

  @Query("select sum(p.amount) from Payment p where p.status = :status")
  BigDecimal sumAmountByStatus(@Param("status") PaymentStatus status);

  @Query(
      "select plan.id as planId, plan.planName as planName, payment.currency as currency, "
          + "count(payment.id) as paymentCount, sum(payment.amount) as revenue "
          + "from Payment payment "
          + "join payment.subscription subscription "
          + "join subscription.plan plan "
          + "where payment.status = :status "
          + "group by plan.id, plan.planName, payment.currency "
          + "order by plan.planName, payment.currency")
  List<PlanRevenueProjection> findRevenueByPlanAndCurrency(
      @Param("status") PaymentStatus status);
}
