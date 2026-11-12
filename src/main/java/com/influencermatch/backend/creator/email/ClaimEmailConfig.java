package com.influencermatch.backend.creator.email;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration @EnableScheduling
public class ClaimEmailConfig {
  @Bean("claimEmailExecutor")
  public ThreadPoolTaskExecutor executor() {
    var executor=new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);executor.setMaxPoolSize(2);executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("claim-email-");executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(20);return executor;
  }
}
