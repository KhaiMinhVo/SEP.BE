package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import com.influencermatch.backend.billing.service.VnPayQueryClient;
import com.influencermatch.backend.billing.utils.VnPayUtil;
import com.influencermatch.backend.config.VnPayConfig;
import java.util.*;
import org.junit.jupiter.api.Test;

class VnPayQueryClientTest {
  final VnPayConfig config=new VnPayConfig();
  final VnPayQueryClient client=new VnPayQueryClient(config);
  @Test void verifiesOriginalAndPromotionResponsesAndRejectsTampering() {
    config.setHashSecret("test-secret");
    String data="RID|querydr|00|Success|TEST|TX|10000|NCB|20261118101000|123|01|00|Query payment TX";
    String[] fields={"vnp_ResponseId","vnp_Command","vnp_ResponseCode","vnp_Message","vnp_TmnCode",
        "vnp_TxnRef","vnp_Amount","vnp_BankCode","vnp_PayDate","vnp_TransactionNo",
        "vnp_TransactionType","vnp_TransactionStatus","vnp_OrderInfo"};
    Map<String,String> response=new HashMap<>(); String[] values=data.split("\\|",-1);
    for(int i=0;i<fields.length;i++) response.put(fields[i],values[i]);
    response.put("vnp_SecureHash",VnPayUtil.hmacSHA512("test-secret",data));
    assertThat(client.verified(response)).isTrue();
    response.put("vnp_Amount","20000"); assertThat(client.verified(response)).isFalse();
    response.put("vnp_Amount","10000"); response.put("vnp_PromotionCode",""); response.put("vnp_PromotionAmount","");
    response.put("vnp_SecureHash",VnPayUtil.hmacSHA512("test-secret",data+"||"));
    assertThat(client.verified(response)).isTrue();
    response.remove("vnp_SecureHash"); assertThat(client.verified(response)).isFalse();
  }
}
