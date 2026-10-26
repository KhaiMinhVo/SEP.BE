package com.influencermatch.backend.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.exception.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpVerificationEmailSenderTest {
  @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider=mock(ObjectProvider.class);
  JavaMailSender mail=mock(JavaMailSender.class);
  String secret="test-email-otp-secret-at-least-32-characters";
  @Test void disabledOrIncompleteConfigurationCannotSend() {
    for(var sender:java.util.List.of(new SmtpVerificationEmailSender(provider,false,"smtp.test","from@example.test",secret),
        new SmtpVerificationEmailSender(provider,true,"","from@example.test",secret),
        new SmtpVerificationEmailSender(provider,true,"smtp.test","",secret),
        new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test","short"),
        new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret))) {
      assertThatThrownBy(sender::requireConfigured).isInstanceOfSatisfying(BusinessException.class,
          e->assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE));
    }
    verifyNoInteractions(mail);
  }
  @Test void sendsOtpToRecipientAndIncludesExpiration() {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret).send("to@example.test","000042");
    var msg=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail).send(msg.capture());
    assertThat(msg.getValue().getTo()).containsExactly("to@example.test");
    assertThat(msg.getValue().getText()).contains("000042","10 minutes");
  }
  @Test void smtpErrorsAreMappedWithoutLeakingServerDetails() {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    doThrow(new MailSendException("private SMTP detail")).when(mail).send(any(SimpleMailMessage.class));
    assertThatThrownBy(()->new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret).send("to@example.test","000042"))
        .isInstanceOfSatisfying(BusinessException.class,e->{assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE);
          assertThat(e.getMessage()).doesNotContain("private SMTP detail");});
  }  @Test void invitationMessageContainsConfiguredLinkAndFortyEightHourLimit() {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret)
        .sendCreatorInvitation("to@example.test","https://frontend.example.test/auth/creator/register?token=random");
    var msg=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail).send(msg.capture());
    assertThat(msg.getValue().getSubject()).contains("Creator invitation");assertThat(msg.getValue().getText()).contains("?token=random","48 hours");
  }
  @Test void passwordSetupMessageHasItsOwnPurpose() {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret).sendPasswordSetup("to@example.test","000042");
    var msg=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail).send(msg.capture());
    assertThat(msg.getValue().getSubject()).contains("Set your account password");assertThat(msg.getValue().getText()).contains("password setup","000042","10 minutes");
  }
  @Test void claimInvitationHasSevenDayLimitAndDoesNotImplyRegistration() {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret)
        .sendCreatorClaimInvitation("to@example.test","https://frontend.example.test/claim-profile?token=random");
    var msg=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail).send(msg.capture());
    assertThat(msg.getValue().getSubject()).contains("Channel claim");
    assertThat(msg.getValue().getText()).contains("7 days","/claim-profile?token=random","does not register","Admin/Data Manager","Resending does not extend its expiry");
  }

}
