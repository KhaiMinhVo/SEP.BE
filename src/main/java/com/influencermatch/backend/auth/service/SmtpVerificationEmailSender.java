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
    deliver(
        email,
        "InfluencerMatch - Xác thực địa chỉ email của bạn",
        "Mã xác thực email của bạn là: "
            + otp
            + "\nMã này có hiệu lực trong vòng 10 phút. Tuyệt đối không chia sẻ mã này với bất kỳ ai.");
  }

  @Override
  public void sendCreatorInvitation(String email, String link) {
    deliver(
        email,
        "InfluencerMatch - Lời mời đăng ký tài khoản Creator",
        "Bạn nhận được lời mời đăng ký tài khoản Creator trên nền tảng InfluencerMatch.\n"
            + link
            + "\nLiên kết này có hiệu lực trong 48 giờ và chỉ sử dụng được 1 lần.");
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
    deliver(
        email,
        "InfluencerMatch - Thư mời hợp tác chiến dịch",
        "Một Brand đã gửi lời mời hợp tác chiến dịch đến bạn trên InfluencerMatch.\n"
            + link
            + "\n"
            + "Liên kết này có hiệu lực trong 7 ngày. Liên kết này không tự động tạo tài khoản, không cấp quyền sở hữu kênh hoặc chấp nhận thỏa thuận.");
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
