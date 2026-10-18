package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.billing.controller.MockPaymentController;
import com.influencermatch.backend.billing.repository.PaymentRepository;
import com.influencermatch.backend.billing.service.*;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MockPaymentConfigurationTest {
  private ApplicationContextRunner runner=new ApplicationContextRunner()
      .withUserConfiguration(MockPaymentController.class,MockPaymentService.class)
      .withBean(PaymentRepository.class,()->mock(PaymentRepository.class))
      .withBean(PaymentSettlementService.class,()->mock(PaymentSettlementService.class))
      .withBean(AuditService.class,()->mock(AuditService.class))
      .withBean(Validator.class,()->mock(Validator.class));
  @Test void requiresExplicitFlagAndDevelopmentProfile() {
    runner.withPropertyValues("spring.profiles.active=dev").run(c->assertThat(c).doesNotHaveBean(MockPaymentController.class));
    runner.withPropertyValues("spring.profiles.active=dev","application.payment.mock.enabled=false")
        .run(c->assertThat(c).doesNotHaveBean(MockPaymentService.class));
    runner.withPropertyValues("spring.profiles.active=prod","application.payment.mock.enabled=true")
        .run(c->assertThat(c).doesNotHaveBean(MockPaymentController.class));
    runner.withPropertyValues("spring.profiles.active=dev,prod","application.payment.mock.enabled=true")
        .run(c->assertThat(c).doesNotHaveBean(MockPaymentController.class));
    runner.withPropertyValues("spring.profiles.active=dev","application.payment.mock.enabled=true")
        .run(c->assertThat(c).hasSingleBean(MockPaymentController.class).hasSingleBean(MockPaymentService.class));
  }
}
