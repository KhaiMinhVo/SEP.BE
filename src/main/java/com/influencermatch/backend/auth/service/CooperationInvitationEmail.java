package com.influencermatch.backend.auth.service;

final class CooperationInvitationEmail {
  static final String SUBJECT = "Bạn có lời mời hợp tác mới | InfluencerMatch";
  static final String SUPPORT = "influencermatch.sep491@gmail.com";
  private CooperationInvitationEmail() {}

  static String text(String link) {
    return """
        Xin chào bạn,

        Một thương hiệu đã gửi đến bạn lời mời hợp tác chiến dịch trên InfluencerMatch.
        Mở liên kết dưới đây để xem thông tin lời mời và hướng dẫn tiếp theo:

        %s

        Liên kết có hiệu lực tối đa 7 ngày kể từ khi lời mời được tạo. Gửi lại email không gia hạn thời hạn này. Lời mời có thể hết hiệu lực sớm nếu được thay thế hoặc hủy.

        Việc mở liên kết không đồng nghĩa bạn đã chấp nhận hợp tác. Liên kết không tự tạo tài khoản hoặc cấp quyền sở hữu kênh. Để xem và phản hồi đề xuất trong hệ thống, bạn cần đăng nhập tài khoản Creator có quyền phù hợp.

        Nếu bạn chưa có tài khoản hoặc cần hỗ trợ, vui lòng liên hệ %s.

        Trân trọng,
        Đội ngũ InfluencerMatch
        """.formatted(link, SUPPORT);
  }

  static String html(String link) {
    var uri = java.net.URI.create(link);
    if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
      throw new IllegalArgumentException("Invalid invitation URL");
    String safeLink = org.springframework.web.util.HtmlUtils.htmlEscape(link, "UTF-8");
    return """
        <!doctype html>
        <html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Lời mời hợp tác InfluencerMatch</title></head>
        <body style="margin:0;padding:0;background:#f5f3fa;font-family:Arial,Helvetica,sans-serif;color:#172033;">
        <div style="display:none;max-height:0;overflow:hidden;mso-hide:all;">Bạn có lời mời hợp tác mới. Xem thông tin và lựa chọn bước tiếp theo trên InfluencerMatch.</div>
        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="background:#f5f3fa;"><tr><td align="center" style="padding:32px 16px;">
        <table role="presentation" width="600" cellspacing="0" cellpadding="0" border="0" style="width:100%%;max-width:600px;background:#ffffff;border:1px solid #e8e2f1;border-radius:16px;">
        <tr><td style="padding:28px 32px 22px;border-bottom:1px solid #eee9f5;"><span style="font-size:22px;line-height:30px;font-weight:700;color:#6d28d9;">InfluencerMatch</span><div style="font-size:12px;line-height:20px;color:#64748b;margin-top:4px;">Kết nối thương hiệu và nhà sáng tạo</div></td></tr>
        <tr><td style="padding:28px 32px;">
        <h1 style="font-size:24px;line-height:34px;margin:0 0 20px;color:#172033;">Bạn có lời mời hợp tác mới</h1>
        <p style="font-size:15px;line-height:25px;margin:0 0 14px;">Xin chào bạn,</p>
        <p style="font-size:15px;line-height:25px;margin:0 0 24px;">Một thương hiệu đã gửi đến bạn lời mời hợp tác chiến dịch trên <strong>InfluencerMatch</strong>. Mở lời mời để xem thông tin và hướng dẫn bước tiếp theo.</p>
        <table role="presentation" cellspacing="0" cellpadding="0" border="0"><tr><td bgcolor="#6d28d9" style="border-radius:8px;mso-padding-alt:14px 24px;"><a href="%s" style="display:inline-block;padding:14px 24px;font-size:15px;line-height:22px;font-weight:700;color:#ffffff;text-decoration:none;border-radius:8px;">Xem lời mời hợp tác</a></td></tr></table>
        <p style="font-size:13px;line-height:22px;color:#64748b;margin:20px 0;">Liên kết có hiệu lực tối đa <strong>7 ngày kể từ khi lời mời được tạo</strong>. Gửi lại email không gia hạn thời hạn này. Lời mời có thể hết hiệu lực sớm nếu được thay thế hoặc hủy.</p>
        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0"><tr><td style="background:#f8f6fc;border:1px solid #eee9f5;border-radius:8px;padding:16px;font-size:13px;line-height:22px;color:#475569;"><strong>Bạn chủ động quyết định việc hợp tác</strong><br>Việc mở liên kết chưa phải là chấp nhận lời mời và không tự tạo tài khoản hoặc cấp quyền sở hữu kênh. Để xem và phản hồi đề xuất trong hệ thống, bạn cần đăng nhập tài khoản Creator có quyền phù hợp.</td></tr></table>
        <p style="font-size:13px;line-height:22px;color:#64748b;margin:22px 0 8px;">Nếu nút phía trên không hoạt động, sao chép liên kết sau vào trình duyệt:</p>
        <p style="font-size:12px;line-height:20px;word-break:break-all;overflow-wrap:anywhere;margin:0 0 24px;"><a href="%s" style="color:#6d28d9;text-decoration:underline;word-break:break-all;">%s</a></p>
        <p style="font-size:15px;line-height:25px;margin:0;">Trân trọng,<br><strong>Đội ngũ InfluencerMatch</strong></p>
        </td></tr>
        <tr><td style="padding:20px 32px;border-top:1px solid #eee9f5;font-size:12px;line-height:21px;color:#64748b;">Cần hỗ trợ hoặc chưa có tài khoản? Liên hệ <a href="mailto:%s" style="color:#6d28d9;text-decoration:none;">%s</a>.<br>Đây là email thông báo lời mời hợp tác từ InfluencerMatch.</td></tr>
        </table></td></tr></table></body></html>
        """.formatted(safeLink, safeLink, safeLink, SUPPORT, SUPPORT);
  }
}
