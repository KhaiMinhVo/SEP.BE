package com.influencermatch.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpCredentialsConfigTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
      .withUserConfiguration(SmtpCredentialsConfig.class)
      .withPropertyValues("spring.mail.username=sender@example.test",
          "spring.mail.password=abcd efgh ijkl mnop");

  @Test
  void gmailUsesAppPasswordWithoutDisplaySpaces() {
    runner.withPropertyValues("spring.mail.host=smtp.gmail.com").run(context ->
        assertThat(context.getBean(JavaMailSenderImpl.class).getPassword())
            .isEqualTo("abcdefghijklmnop"));
  }

  @Test
  void otherProvidersKeepTheirPasswordUnchanged() {
    runner.withPropertyValues("spring.mail.host=smtp.example.test").run(context ->
        assertThat(context.getBean(JavaMailSenderImpl.class).getPassword())
            .isEqualTo("abcd efgh ijkl mnop"));
  }
}
