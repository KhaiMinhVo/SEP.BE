package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.exception.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class SmtpVerificationEmailSender implements VerificationEmailSender {
  private final ObjectProvider<JavaMailSender> sender;
  private final boolean enabled;
  private final String host, from, secret;

  public SmtpVerificationEmailSender(
      ObjectProvider<JavaMailSender> sender,
      @Value("${application.auth.email-verification.enabled:false}") boolean enabled,
      @Value("${spring.mail.host:}") String host,
      @Value("${application.auth.email-verification.from:}") String from,
      @Value("${application.auth.email-verification.secret:}") String secret) {
    this.sender = sender;
    this.enabled = enabled;
    this.host = host;
    this.from = from;
    this.secret = secret;
  }

  @Override
  public void requireConfigured() {
    if (!enabled
        || host.isBlank()
        || from.isBlank()
        || secret.length() < 32
        || sender.getIfAvailable() == null)
      throw new BusinessException(
          ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,
          "Configure SMTP and an OTP secret of at least 32 characters");
  }
  @Override
  public void send(String email, String otp) {
    deliverHtml(email, EmailVerificationTemplate.SUBJECT, EmailVerificationTemplate.text(otp), EmailVerificationTemplate.html(otp));
  }

  @Override
  public void sendCreatorInvitation(String email, String link) {
    deliverHtml(email, CreatorRegistrationEmail.SUBJECT, CreatorRegistrationEmail.text(link), CreatorRegistrationEmail.html(link));
  }

  @Override
  public void sendCooperationAccountAccess(String email, String link, boolean creatorAccount) {
    deliver(email, "InfluencerMatch - Hướng dẫn tiếp tục lời mời hợp tác",
        creatorAccount
            ? "Email này đã có tài khoản Creator trên InfluencerMatch. Bạn không cần đăng ký lại.\n"
                + "Mở lời mời và đăng nhập bằng tài khoản hiện có:\n" + link
                + "\nNếu quên mật khẩu, chọn Quên mật khẩu tại màn hình đăng nhập."
                + "\nMở liên kết không tự chấp nhận đề xuất hoặc cấp quyền sở hữu kênh."
            : "Email này đã gắn với một tài khoản InfluencerMatch có vai trò khác Creator.\n"
                + "Hệ thống hiện chưa hỗ trợ nhiều vai trò trên cùng tài khoản, nên không tạo thêm tài khoản Creator bằng email này."
                + "\nVui lòng liên hệ influencermatch.sep491@gmail.com hoặc thương hiệu gửi lời mời để được hỗ trợ."
                + "\nKhông gửi mật khẩu, OTP hoặc token cho người khác.");
  }

  @Override
  public void sendCreatorClaimInvitation(String email, String link) {
    deliver(
        email,
        "InfluencerMatch - Thư mời xác nhận quyền sở hữu kênh Creator",
        "Bạn nhận được lời mời xác nhận quyền sở hữu kênh Creator trên InfluencerMatch.\n"
            + link
            + "\n"
            + "Liên kết này có hiệu lực trong vòng 7 ngày kể từ khi được tạo. Việc gửi lại thư mời không gia hạn thời hạn này (Resending does not extend its expiry). Vui lòng đăng nhập bằng tài khoản Creator đã xác thực đúng địa chỉ email này. Nếu bạn chưa có tài khoản, vui lòng liên hệ Admin/Data Manager để nhận thư mời đăng ký. Liên kết này không tự động tạo tài khoản hoặc cấp quyền sở hữu ngay lập tức (does not register an account or grant ownership).");
  }

  @Override
  public void sendCooperationInvitation(String email, String link) {
    deliverHtml(email, CooperationInvitationEmail.SUBJECT, CooperationInvitationEmail.text(link), CooperationInvitationEmail.html(link));
  }

  private void deliverHtml(String email, String subject, String text, String html) {
    requireConfigured();
    try {
      var message = sender.getObject().createMimeMessage();
      var helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(from, "InfluencerMatch");
      helper.setTo(email);
      helper.setReplyTo(CooperationInvitationEmail.SUPPORT);
      helper.setSubject(subject);
      helper.setText(text, html);
      sender.getObject().send(message);
    } catch (MailException | jakarta.mail.MessagingException | java.io.UnsupportedEncodingException ex) {
      throw new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE, "Unable to send email. Please try again.");
    }
  }

  @Override
  public void sendPasswordSetup(String email, String otp) {
    deliver(
        email,
        "InfluencerMatch - Thiết lập mật khẩu tài khoản của bạn",
        "Mã thiết lập mật khẩu của bạn là: "
            + otp
            + "\nMã này có hiệu lực trong vòng 10 phút. Tuyệt đối không chia sẻ mã này với bất kỳ ai.");
  }

  @Override
  public void sendPasswordReset(String email, String otp) {
    deliver(
        email,
        "InfluencerMatch - Đặt lại mật khẩu",
        "Mã đặt lại mật khẩu của bạn là: " + otp
            + "\nMã có hiệu lực trong 10 phút. Không chia sẻ mã này với người khác."
            + "\nNếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này.");
  }

  private void deliver(String email, String subject, String text) {
    requireConfigured();
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(email);
    message.setSubject(subject);
    message.setText(text);
    try {
      sender.getObject().send(message);
    } catch (MailException ex) {
      throw new BusinessException(
          ErrorCode.EMAIL_DELIVERY_UNAVAILABLE, "Unable to send email. Please try again.");
    }
  }
}
