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
  @Test void sendsOtpToRecipientAndIncludesExpiration() throws Exception {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    var mime = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()));
    when(mail.createMimeMessage()).thenReturn(mime);
    new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret).send("to@example.test","000042");
    verify(mail).send(mime);mime.saveChanges();
    assertThat(mime.getAllRecipients()[0].toString()).isEqualTo("to@example.test");
    assertThat(mime.getSubject()).isEqualTo(EmailVerificationTemplate.SUBJECT).doesNotContain("000042");
    assertThat(mimeContent(mime)).contains("text/plain", "text/html", "UTF-8", "000042", "10 phút", "mã mới nhất", "Không chia sẻ");
  }
  @Test void smtpErrorsAreMappedWithoutLeakingServerDetails() {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    var mime = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()));
    when(mail.createMimeMessage()).thenReturn(mime);
    doThrow(new MailSendException("private SMTP detail")).when(mail).send(mime);
    assertThatThrownBy(()->new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret).send("to@example.test","000042"))
        .isInstanceOfSatisfying(BusinessException.class,e->{assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE);
          assertThat(e.getMessage()).doesNotContain("private SMTP detail");});
  }  @Test void invitationMessageContainsConfiguredLinkAndFortyEightHourLimit() throws Exception {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    var mime = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()));
    when(mail.createMimeMessage()).thenReturn(mime);
    new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret)
        .sendCreatorInvitation("to@example.test","https://frontend.example.test/auth/creator/register?token=random&cooperationToken=test");
    verify(mail).send(mime);mime.saveChanges();
    assertThat(mime.getSubject()).contains("Creator", "InfluencerMatch");
    assertThat(mime.getAllRecipients()[0].toString()).isEqualTo("to@example.test");
    assertThat(mime.getReplyTo()[0].toString()).isEqualTo(CooperationInvitationEmail.SUPPORT);
    assertThat(mimeContent(mime)).contains("text/plain", "text/html", "UTF-8", "48 giờ", "1 lần", "Tạo tài khoản Creator",
        "href=\"https://frontend.example.test/auth/creator/register?token=random&amp;cooperationToken=test\"", "không tự cấp quyền sở hữu");
  }
  @Test void invalidOtpCannotBeInsertedIntoHtml() {
    var sender = new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret);
    assertThatThrownBy(() -> sender.send("to@example.test","<script>"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(mail);
  }
  @Test void creatorRegistrationRejectsUnsafeLinksBeforeSending() {
    var sender = new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret);
    assertThatThrownBy(() -> sender.sendCreatorInvitation("to@example.test","javascript:alert(1)"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sender.sendCreatorInvitation("to@example.test","https://user:password@frontend.example.test/register"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(mail);
  }
  @Test void creatorRegistrationSmtpFailureDoesNotExposeProviderDetails() {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    var mime = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()));
    when(mail.createMimeMessage()).thenReturn(mime);
    doThrow(new MailSendException("private SMTP detail")).when(mail).send(mime);
    assertThatThrownBy(() -> new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret)
        .sendCreatorInvitation("to@example.test","https://frontend.example.test/auth/creator/register?token=test"))
        .isInstanceOfSatisfying(BusinessException.class,e->{assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE);
          assertThat(e.getMessage()).doesNotContain("private SMTP detail");});
  }
  @Test void passwordSetupMessageHasItsOwnPurpose() {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret).sendPasswordSetup("to@example.test","000042");
    var msg=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail).send(msg.capture());
    assertThat(msg.getValue().getSubject()).contains("Thiết lập mật khẩu");assertThat(msg.getValue().getText()).contains("000042","10 phút");
  }
  @Test void existingCreatorGetsLoginGuidanceAndOtherRolesGetSupportGuidance() {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    var sender = new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret);
    String link = "https://frontend.example.test/cooperation-invitations?token=private";
    sender.sendCooperationAccountAccess("creator@example.test",link,true);
    sender.sendCooperationAccountAccess("brand@example.test",link,false);
    var msg=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail,times(2)).send(msg.capture());
    assertThat(msg.getAllValues().get(0).getTo()).containsExactly("creator@example.test");
    assertThat(msg.getAllValues().get(0).getText()).contains(link,"không cần đăng ký lại");
    assertThat(msg.getAllValues().get(1).getTo()).containsExactly("brand@example.test");
    assertThat(msg.getAllValues().get(1).getText()).contains("influencermatch.sep491@gmail.com").doesNotContain(link);
  }
  @Test void claimInvitationHasSevenDayLimitAndDoesNotImplyRegistration() {
    when(provider.getIfAvailable()).thenReturn(mail);when(provider.getObject()).thenReturn(mail);
    new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret)
        .sendCreatorClaimInvitation("to@example.test","https://frontend.example.test/claim-profile?token=random");
    var msg=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail).send(msg.capture());
    assertThat(msg.getValue().getSubject()).contains("quyền sở hữu");
    assertThat(msg.getValue().getText()).contains("7 ngày","/claim-profile?token=random","Admin/Data Manager");
  }

  @Test void cooperationEmailHasUtf8HtmlAndPlainTextAlternatives() throws Exception {
    when(provider.getIfAvailable()).thenReturn(mail); when(provider.getObject()).thenReturn(mail);
    var mime = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()));
    when(mail.createMimeMessage()).thenReturn(mime);
    String link = "https://frontend.example.test/cooperation-invitations?token=test&source=email";
    new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret).sendCooperationInvitation("to@example.test",link);
    verify(mail).send(mime);
    mime.saveChanges();
    assertThat(mime.getSubject()).contains("lời mời hợp tác", "InfluencerMatch");
    assertThat(mime.getAllRecipients()[0].toString()).isEqualTo("to@example.test");
    assertThat(mime.getReplyTo()[0].toString()).isEqualTo(CooperationInvitationEmail.SUPPORT);
    String content = mimeContent(mime);
    assertThat(content).contains("text/plain", "text/html", "UTF-8", "Xem lời mời hợp tác", "7 ngày", "không gia hạn", "href=\"https://frontend.example.test/cooperation-invitations?token=test&amp;source=email\"", "không tự tạo tài khoản");
    assertThat(content).doesNotContain("<script");
  }

  private static String mimeContent(jakarta.mail.Part part) throws Exception {
    if (part.getContent() instanceof jakarta.mail.Multipart multipart) {
      var result = new StringBuilder();
      for (int i=0; i<multipart.getCount(); i++) result.append(mimeContent(multipart.getBodyPart(i)));
      return result.toString();
    }
    return part.getContentType() + "\n" + part.getContent();
  }

  @Test void cooperationEmailRejectsNonWebLinks() {
    when(provider.getIfAvailable()).thenReturn(mail);
    assertThatThrownBy(() -> new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret).sendCooperationInvitation("to@example.test","javascript:alert(1)"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(mail);
  }

  @Test void cooperationHtmlSmtpFailureIsMappedWithoutProviderDetails() {
    when(provider.getIfAvailable()).thenReturn(mail); when(provider.getObject()).thenReturn(mail);
    var mime = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()));
    when(mail.createMimeMessage()).thenReturn(mime);
    doThrow(new MailSendException("private SMTP detail")).when(mail).send(mime);
    assertThatThrownBy(() -> new SmtpVerificationEmailSender(provider,true,"smtp.test","from@example.test",secret).sendCooperationInvitation("to@example.test","https://frontend.example.test/cooperation-invitations?token=test"))
      .isInstanceOfSatisfying(BusinessException.class, e -> { assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE); assertThat(e.getMessage()).doesNotContain("private SMTP detail"); });
  }

}
