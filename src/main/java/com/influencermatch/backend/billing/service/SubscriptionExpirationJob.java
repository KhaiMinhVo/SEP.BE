package com.influencermatch.backend.billing.service;

import com.influencermatch.backend.billing.repository.SubscriptionRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class SubscriptionExpirationJob {
  private final SubscriptionRepository subscriptions;
  @Scheduled(cron = "0 0 * * * *")
  @Transactional
  public void expire() { subscriptions.expireSubscriptions(LocalDate.now()); }
}
