package com.influencermatch.backend.auth.service;

final class CreatorRegistrationEmail {
  static final String SUBJECT = "Hoàn tất đăng ký Creator | InfluencerMatch";
  private static final String SUPPORT = CooperationInvitationEmail.SUPPORT;
  private CreatorRegistrationEmail() {}

  static String text(String link) {
    return """
        Xin chào bạn,

        Bạn nhận được lời mời đăng ký tài khoản Creator trên InfluencerMatch.
        Mở liên kết dưới đây để điền thông tin và tạo tài khoản:

        %s

        Liên kết có hiệu lực trong 48 giờ kể từ khi được tạo và chỉ sử dụng được 1 lần.
        Nếu yêu cầu email đăng ký mới, hãy sử dụng liên kết mới nhất.

        Sau khi tạo tài khoản, bạn cần xác minh email bằng OTP và liên kết đúng kênh
        trước khi phản hồi đề xuất hợp tác. Đăng ký không tự xác nhận hợp tác.

        Nếu bạn không yêu cầu đăng ký, có thể bỏ qua email này.
        Cần hỗ trợ? Liên hệ %s.

        Trân trọng,
        Đội ngũ InfluencerMatch
        """.formatted(link, SUPPORT);
  }

  static String html(String link) {
    var uri = java.net.URI.create(link);
    if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
      throw new IllegalArgumentException("Invalid Creator registration URL");
    String safeLink = org.springframework.web.util.HtmlUtils.htmlEscape(link, "UTF-8");
    return """
        <!doctype html>
        <html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Đăng ký Creator | InfluencerMatch</title></head>
        <body style="margin:0;padding:0;background:#f5f3fa;font-family:Arial,Helvetica,sans-serif;color:#172033;">
        <div style="display:none;max-height:0;overflow:hidden;mso-hide:all;">Tạo tài khoản Creator để tiếp tục lời mời hợp tác. Liên kết có hiệu lực trong 48 giờ.</div>
        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="background:#f5f3fa;"><tr><td align="center" style="padding:32px 16px;">
        <table role="presentation" width="600" cellspacing="0" cellpadding="0" border="0" style="width:100%%;max-width:600px;background:#ffffff;border:1px solid #e8e2f1;border-radius:16px;">
        <tr><td style="padding:26px 24px 22px;border-bottom:1px solid #eee9f5;"><span style="font-size:22px;line-height:30px;font-weight:700;color:#6d28d9;">InfluencerMatch</span><div style="font-size:12px;line-height:20px;color:#64748b;margin-top:4px;">Kết nối thương hiệu và nhà sáng tạo</div></td></tr>
        <tr><td style="padding:28px 24px;">
        <h1 style="font-size:25px;line-height:34px;margin:0 0 18px;color:#172033;">Chào mừng nhà sáng tạo!</h1>
        <p style="font-size:15px;line-height:25px;margin:0 0 14px;">Xin chào bạn,</p>
        <p style="font-size:15px;line-height:25px;margin:0 0 24px;">Bạn nhận được lời mời đăng ký tài khoản <strong>Creator</strong> trên InfluencerMatch. Hoàn tất đăng ký để tiếp tục lời mời hợp tác và quản lý kênh của bạn.</p>
        <table role="presentation" cellspacing="0" cellpadding="0" border="0"><tr><td bgcolor="#6d28d9" style="border-radius:8px;mso-padding-alt:14px 24px;"><a href="%s" style="display:inline-block;padding:14px 24px;font-size:15px;line-height:22px;font-weight:700;color:#ffffff;text-decoration:none;border-radius:8px;">Tạo tài khoản Creator &rarr;</a></td></tr></table>
        <p style="font-size:13px;line-height:22px;color:#64748b;margin:18px 0 24px;">Hiệu lực <strong>48 giờ</strong> kể từ khi tạo &middot; Chỉ sử dụng <strong>1 lần</strong>.<br>Nếu yêu cầu email mới, hãy sử dụng liên kết mới nhất.</p>
        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0"><tr><td style="background:#f8f6fc;border:1px solid #eee9f5;border-radius:8px;padding:16px;font-size:13px;line-height:23px;color:#475569;"><strong>Sau khi đăng ký</strong><br>Xác minh email bằng OTP, sau đó liên kết đúng kênh để xem và phản hồi đề xuất hợp tác. Đăng ký không tự cấp quyền sở hữu kênh hoặc xác nhận hợp tác.</td></tr></table>
        <p style="font-size:12px;line-height:20px;color:#64748b;margin:22px 0 6px;">Nút không hoạt động? Sao chép liên kết dưới đây vào trình duyệt:</p>
        <p style="font-size:12px;line-height:20px;word-break:break-all;overflow-wrap:anywhere;margin:0 0 22px;"><a href="%s" style="color:#6d28d9;text-decoration:underline;word-break:break-all;">%s</a></p>
        <p style="font-size:14px;line-height:24px;margin:0;">Trân trọng,<br><strong>Đội ngũ InfluencerMatch</strong></p>
        </td></tr>
        <tr><td style="padding:18px 24px;border-top:1px solid #eee9f5;font-size:12px;line-height:21px;color:#64748b;">Không yêu cầu đăng ký? Bạn có thể bỏ qua email này.<br>Cần hỗ trợ? <a href="mailto:%s" style="color:#6d28d9;text-decoration:none;">%s</a></td></tr>
        </table></td></tr></table></body></html>
        """.formatted(safeLink, safeLink, safeLink, SUPPORT, SUPPORT);
  }
}
