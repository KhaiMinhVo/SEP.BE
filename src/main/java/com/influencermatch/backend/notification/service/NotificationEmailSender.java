package com.influencermatch.backend.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Luồng 15.1: Asynchronous, fault-tolerant email dispatch for critical notifications.
 * Delivery failures are logged and do not corrupt the primary transaction or DB state.
 */
@Slf4j
@Service
public class NotificationEmailSender {

  private final ObjectProvider<JavaMailSender> mailSenderProvider;
  private final String fromEmail;

  public NotificationEmailSender(
      ObjectProvider<JavaMailSender> mailSenderProvider,
      @Value("${application.auth.email-verification.from:no-reply@influencermatch.com}") String fromEmail) {
    this.mailSenderProvider = mailSenderProvider;
    this.fromEmail = fromEmail;
  }

  @Async
  public void sendEmailAsync(String recipientEmail, String subject, String content) {
    if (recipientEmail == null || recipientEmail.isBlank()) {
      log.debug("Skipping email dispatch: recipient email is blank");
      return;
    }

    JavaMailSender sender = mailSenderProvider.getIfAvailable();
    if (sender == null) {
      log.debug("JavaMailSender is not configured; skipping email notification to {}", recipientEmail);
      return;
    }

    SimpleMailMessage mailMessage = new SimpleMailMessage();
    mailMessage.setFrom(fromEmail);
    mailMessage.setTo(recipientEmail);
    mailMessage.setSubject("InfluencerMatch - " + subject);
    mailMessage.setText(content);

    int maxAttempts = 3;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        sender.send(mailMessage);
        log.info("Notification email dispatched successfully to {}", recipientEmail);
        return;
      } catch (Exception ex) {
        if (attempt == maxAttempts) {
          log.warn(
              "Failed to dispatch notification email to {} after {} attempts: {}",
              recipientEmail,
              maxAttempts,
              ex.getMessage());
        } else {
          log.debug(
              "Attempt {} failed to dispatch email to {}: {}. Retrying...",
              attempt,
              recipientEmail,
              ex.getMessage());
          try {
            Thread.sleep(500L * attempt);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Email retry interrupted for {}", recipientEmail);
            return;
          }
        }
      }
    }
  }
}

