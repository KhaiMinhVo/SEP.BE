package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.exception.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class SmtpVerificationEmailSender implements VerificationEmailSender {
  private final ObjectProvider<JavaMailSender> sender;
  private final boolean enabled;
  private final String host,from,secret;
  public SmtpVerificationEmailSender(ObjectProvider<JavaMailSender> sender,
      @Value("${application.auth.email-verification.enabled:false}") boolean enabled,
      @Value("${spring.mail.host:}") String host,
      @Value("${application.auth.email-verification.from:}") String from,
      @Value("${application.auth.email-verification.secret:}") String secret) {
    this.sender=sender;this.enabled=enabled;this.host=host;this.from=from;this.secret=secret;
  }
  @Override public void requireConfigured() {
    if(!enabled || host.isBlank() || from.isBlank() || secret.length()<32 || sender.getIfAvailable()==null)
      throw new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Configure SMTP and an OTP secret of at least 32 characters");
  }
  @Override public void send(String email,String otp) {
    deliver(email,"InfluencerMatch - Verify your email","Your verification code is: "+otp+"\nThis code expires in 10 minutes. Do not share this code.");
  }
  @Override public void sendCreatorInvitation(String email,String link) {
    deliver(email,"InfluencerMatch - Creator invitation","You are invited to register as a Creator.\n"+link+"\nThis link expires in 48 hours and can only be used once.");
  }
  @Override public void sendCreatorClaimInvitation(String email,String link) {
    deliver(email,"InfluencerMatch - Channel claim invitation","You are invited to claim a channel.\n"+link+
        "\nThis link expires 7 days after the invitation was created. Resending does not extend its expiry. Sign in with a verified Creator account using this email. If you do not have an account, ask Admin/Data Manager for a registration invitation. This link does not register an account or grant ownership.");
  }
  @Override public void sendPasswordSetup(String email,String otp) {
    deliver(email,"InfluencerMatch - Set your account password","Your password setup code is: "+otp+"\nThis code expires in 10 minutes. Do not share this code.");
  }
  private void deliver(String email,String subject,String text) {
    requireConfigured();var message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);
    message.setSubject(subject);message.setText(text);
    try {sender.getObject().send(message);}
    catch(MailException ex) {throw new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unable to send email. Please try again.");}
  }
}
