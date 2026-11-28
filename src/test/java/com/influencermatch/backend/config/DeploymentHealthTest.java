package com.influencermatch.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

class DeploymentHealthTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(context -> {
            try {
              for (var source : new YamlPropertySourceLoader()
                  .load("application", new ClassPathResource("application.yml"))) {
                context.getEnvironment().getPropertySources().addLast(source);
              }
            } catch (java.io.IOException ex) {
              throw new IllegalStateException(ex);
            }
          })
          .withConfiguration(AutoConfigurations.of(HealthEndpointAutoConfiguration.class));

  @Test
  void mailFailureRemainsVisibleButDoesNotBlockDeployment() {
    runner
        .withBean("db", HealthIndicator.class, () -> () -> Health.up().build())
        .withBean("mail", HealthIndicator.class, () -> () -> Health.down().build())
        .run(context -> {
          var endpoint = context.getBean(HealthEndpoint.class);
          assertThat(endpoint.health().getStatus()).isEqualTo(Status.DOWN);
          assertThat(endpoint.healthForPath("deployment").getStatus()).isEqualTo(Status.UP);
        });
  }

  @Test
  void databaseFailureStillBlocksDeploymentWhenMailIsNotConfigured() {
    runner
        .withBean("db", HealthIndicator.class, () -> () -> Health.down().build())
        .run(context -> assertThat(context.getBean(HealthEndpoint.class)
            .healthForPath("deployment").getStatus()).isEqualTo(Status.DOWN));
  }
}
