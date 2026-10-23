package com.influencermatch.backend.config;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration
public class EmailVerificationConfig {
  @Bean @ConditionalOnMissingBean(Clock.class)
  Clock verificationClock() { return Clock.systemUTC(); }
}
