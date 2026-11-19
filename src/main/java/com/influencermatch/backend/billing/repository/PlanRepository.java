package com.influencermatch.backend.billing.repository;

import com.influencermatch.backend.billing.enums.PlanStatus;
import com.influencermatch.backend.billing.model.Plan;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PlanRepository extends JpaRepository<Plan, UUID> {
  Page<Plan> findByStatus(PlanStatus status, Pageable pageable);

  boolean existsByPlanNameIgnoreCase(String planName);

  boolean existsByPlanNameIgnoreCaseAndIdNot(String planName, UUID id);

  /** ACTIVE, unexpired subscriptions per plan; rows are [planId, count]. */
  @Query(
      "select s.plan.id, count(s) from Subscription s"
          + " where s.plan.id in :planIds"
          + " and s.status = com.influencermatch.backend.billing.enums.SubscriptionStatus.ACTIVE"
          + " and s.expirationDate >= CURRENT_DATE"
          + " group by s.plan.id")
  List<Object[]> countActiveSubscriptionsByPlan(@Param("planIds") Collection<UUID> planIds);
}
