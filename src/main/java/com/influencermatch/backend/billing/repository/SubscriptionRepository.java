package com.influencermatch.backend.billing.repository;

import com.influencermatch.backend.billing.controller.*;
import com.influencermatch.backend.billing.dto.*;
import com.influencermatch.backend.billing.enums.*;
import com.influencermatch.backend.billing.model.*;
import com.influencermatch.backend.billing.service.*;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
  List<Subscription> findByBrandProfileId(UUID brandProfileId);

  List<Subscription> findByBrandProfileIdAndStatus(UUID brandProfileId, SubscriptionStatus status);

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select s from Subscription s where s.id = :id")
  java.util.Optional<Subscription> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query("update Subscription s set s.status = "
      + "case when s.cancelAtPeriodEnd = true then com.influencermatch.backend.billing.enums.SubscriptionStatus.CANCELLED "
      + "else com.influencermatch.backend.billing.enums.SubscriptionStatus.EXPIRED end "
      + "where s.status = com.influencermatch.backend.billing.enums.SubscriptionStatus.ACTIVE and s.expirationDate < :today")
  int expireSubscriptions(@org.springframework.data.repository.query.Param("today") java.time.LocalDate today);

  long countByStatus(SubscriptionStatus status);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE Subscription s SET s.usedCampaignQuota = s.usedCampaignQuota + 1 "
          + "WHERE s.id = :id AND s.status = com.influencermatch.backend.billing.enums.SubscriptionStatus.ACTIVE "
          + "AND s.refundHold = false AND s.expirationDate >= :today AND s.usedCampaignQuota < s.purchasedCampaignQuota")
  int reserveCampaignQuotaAtomically(
      @org.springframework.data.repository.query.Param("id") UUID id,
      @org.springframework.data.repository.query.Param("today") java.time.LocalDate today);

  boolean existsByBrandProfileIdAndStatusAndExpirationDateGreaterThanEqual(
      UUID brandProfileId, SubscriptionStatus status, java.time.LocalDate today);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE Subscription s SET s.usedRecommendationQuota = s.usedRecommendationQuota + 1 " +
      "WHERE s.id = :id AND s.status = com.influencermatch.backend.billing.enums.SubscriptionStatus.ACTIVE AND s.refundHold = false AND s.expirationDate >= CURRENT_DATE AND s.usedRecommendationQuota < s.purchasedRecommendationQuota")
  int reserveQuotaAtomically(@org.springframework.data.repository.query.Param("id") UUID subscriptionId);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE Subscription s SET s.usedRecommendationQuota = s.usedRecommendationQuota - 1 " +
      "WHERE s.id = :id AND s.usedRecommendationQuota > 0")
  int releaseQuotaReservation(@org.springframework.data.repository.query.Param("id") UUID subscriptionId);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query("UPDATE Subscription s SET s.usedRefreshQuota=s.usedRefreshQuota+1 "
      + "WHERE s.id=:id AND s.status=com.influencermatch.backend.billing.enums.SubscriptionStatus.ACTIVE "
      + "AND s.refundHold=false AND s.startDate<=:today AND s.expirationDate>=:today "
      + "AND s.usedRefreshQuota<s.purchasedRefreshQuota")
  int reserveRefreshQuotaAtomically(@org.springframework.data.repository.query.Param("id") UUID id,
      @org.springframework.data.repository.query.Param("today") java.time.LocalDate today);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query("UPDATE Subscription s SET s.usedRefreshQuota=s.usedRefreshQuota-1 "
      + "WHERE s.id=:id AND s.usedRefreshQuota>0")
  int releaseRefreshQuota(@org.springframework.data.repository.query.Param("id") UUID id);
}
