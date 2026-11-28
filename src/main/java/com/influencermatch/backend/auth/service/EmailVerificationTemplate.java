package com.influencermatch.backend.auth.service;

final class EmailVerificationTemplate {
  static final String SUBJECT = "Xác minh địa chỉ email | InfluencerMatch";
  private static final String SUPPORT = CooperationInvitationEmail.SUPPORT;
  private EmailVerificationTemplate() {}

  private static void validate(String otp) {
    if (otp == null || !otp.matches("[0-9]{6}")) throw new IllegalArgumentException("Invalid verification code");
  }

  static String text(String otp) {
    validate(otp);
    return """
        Xin chào bạn,

        Nhập mã dưới đây trên màn hình xác minh email của InfluencerMatch:

        %s

        Mã có hiệu lực trong 10 phút. Nếu yêu cầu gửi lại, hãy sử dụng mã mới nhất.
        Không chia sẻ mã này với bất kỳ ai, kể cả người tự xưng là nhân viên InfluencerMatch.

        Nếu bạn không yêu cầu xác minh, có thể bỏ qua email này.
        Cần hỗ trợ? Liên hệ %s.

        Trân trọng,
        Đội ngũ InfluencerMatch
        """.formatted(otp, SUPPORT);
  }

  static String html(String otp) {
    validate(otp);
    return """
        <!doctype html>
        <html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Xác minh email | InfluencerMatch</title></head>
        <body style="margin:0;padding:0;background:#f5f3fa;font-family:Arial,Helvetica,sans-serif;color:#172033;">
        <div style="display:none;max-height:0;overflow:hidden;mso-hide:all;">Hoàn tất xác minh email của bạn trên InfluencerMatch. Mã có hiệu lực trong 10 phút.</div>
        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="background:#f5f3fa;"><tr><td align="center" style="padding:32px 16px;">
        <table role="presentation" width="560" cellspacing="0" cellpadding="0" border="0" style="width:100%%;max-width:560px;background:#ffffff;border:1px solid #e8e2f1;border-radius:16px;">
        <tr><td style="padding:26px 24px 22px;border-bottom:1px solid #eee9f5;"><span style="font-size:22px;line-height:30px;font-weight:700;color:#6d28d9;">InfluencerMatch</span><div style="font-size:12px;line-height:20px;color:#64748b;margin-top:4px;">Kết nối thương hiệu và nhà sáng tạo</div></td></tr>
        <tr><td style="padding:28px 24px;">
        <h1 style="font-size:25px;line-height:34px;margin:0 0 18px;color:#172033;">Xác minh địa chỉ email</h1>
        <p style="font-size:15px;line-height:25px;margin:0 0 12px;">Xin chào bạn,</p>
        <p style="font-size:15px;line-height:25px;margin:0 0 24px;">Nhập mã dưới đây trên màn hình xác minh email của <strong>InfluencerMatch</strong> để tiếp tục.</p>
        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0"><tr><td align="center" style="background:#f8f6fc;border:1px solid #e8e2f1;border-radius:12px;padding:22px 12px;">
        <div style="font-size:11px;line-height:18px;font-weight:700;letter-spacing:1px;color:#64748b;margin-bottom:8px;">MÃ XÁC MINH</div>
        <div dir="ltr" style="font-family:Consolas,'Courier New',monospace;font-size:34px;line-height:44px;letter-spacing:6px;font-weight:700;color:#6d28d9;white-space:nowrap;">%s</div>
        </td></tr></table>
        <p style="font-size:13px;line-height:22px;color:#64748b;margin:16px 0 22px;">Mã có hiệu lực trong <strong>10 phút</strong>. Nếu yêu cầu gửi lại, hãy sử dụng mã mới nhất.</p>
        <p style="font-size:13px;line-height:22px;color:#475569;margin:0 0 22px;"><strong>Giữ mã xác minh riêng tư.</strong> Không chia sẻ mã với bất kỳ ai, kể cả người tự xưng là nhân viên InfluencerMatch.</p>
        <p style="font-size:14px;line-height:24px;margin:0;">Trân trọng,<br><strong>Đội ngũ InfluencerMatch</strong></p>
        </td></tr>
        <tr><td style="padding:18px 24px;border-top:1px solid #eee9f5;font-size:12px;line-height:21px;color:#64748b;">Không yêu cầu xác minh? Bạn có thể bỏ qua email này.<br>Cần hỗ trợ? <a href="mailto:%s" style="color:#6d28d9;text-decoration:none;">%s</a></td></tr>
        </table></td></tr></table></body></html>
        """.formatted(otp, SUPPORT, SUPPORT);
  }
}
