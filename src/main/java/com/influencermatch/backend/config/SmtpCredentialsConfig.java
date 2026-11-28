package com.influencermatch.backend.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration(proxyBeanMethods = false)
public class SmtpCredentialsConfig {
  @Bean
  static BeanPostProcessor gmailAppPasswordFormatting() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof JavaMailSenderImpl sender
            && "smtp.gmail.com".equalsIgnoreCase(sender.getHost())
            && sender.getPassword() != null) {
          // Google displays App Passwords in spaced groups. Spaces are not part of the credential.
          sender.setPassword(sender.getPassword().replaceAll("\\s", ""));
        }
        return bean;
      }
    };
  }
}
