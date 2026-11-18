package com.influencermatch.backend.billing;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.billing.service.*;
import com.influencermatch.backend.billing.utils.VnPayUtil;
import com.influencermatch.backend.config.VnPayConfig;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class VnPayRefundClientTest {
  final VnPayConfig config = new VnPayConfig();
  final RestClient.Builder builder = RestClient.builder();
  final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  final VnPayRefundClient client = new VnPayRefundClient(config, new VnPayQueryClient(config), builder);
  final VnPayRefundClient.Submission submission = new VnPayRefundClient.Submission(UUID.randomUUID(), "RID", "TX",
      new BigDecimal("100000"), "20261118100000", "admin", "20261118110000");
  final ObjectMapper json = new ObjectMapper();
  @BeforeEach void setup() { config.setTmnCode("TEST"); config.setHashSecret("unit-secret"); }
  Map<String, String> response() {
    return new LinkedHashMap<>(Map.ofEntries(Map.entry("vnp_ResponseId", "RESPONSE"), Map.entry("vnp_Command", "refund"),
        Map.entry("vnp_ResponseCode", "00"), Map.entry("vnp_Message", "Success"), Map.entry("vnp_TmnCode", "TEST"),
        Map.entry("vnp_TxnRef", "TX"), Map.entry("vnp_Amount", "10000000"), Map.entry("vnp_BankCode", "NCB"),
        Map.entry("vnp_PayDate", "20261118110000"), Map.entry("vnp_TransactionNo", "123456"),
        Map.entry("vnp_TransactionType", "02"), Map.entry("vnp_TransactionStatus", "00"), Map.entry("vnp_OrderInfo", "Full refund TX")));
  }
  void sign(Map<String, String> response) {
    String[] fields = {"vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message", "vnp_TmnCode", "vnp_TxnRef",
        "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo", "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo"};
    response.put("vnp_SecureHash", VnPayUtil.hmacSHA512("unit-secret", Arrays.stream(fields)
        .map(k -> response.getOrDefault(k, "")).collect(java.util.stream.Collectors.joining("|"))));
  }
  @Test void signsFullRefundInPublishedFieldOrderAndChecksResponse() throws Exception {
    var response = response(); sign(response);
    String signed = "RID|2.1.0|refund|TEST|02|TX|10000000||20261118100000|admin|20261118110000|127.0.0.1|Full refund TX";
    server.expect(requestTo(config.getQueryUrl())).andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.vnp_TransactionType").value("02"))
        .andExpect(jsonPath("$.vnp_Amount").value("10000000"))
        .andExpect(jsonPath("$.vnp_TransactionDate").value("20261118100000"))
        .andExpect(jsonPath("$.vnp_SecureHash").value(VnPayUtil.hmacSHA512("unit-secret", signed)))
        .andRespond(withSuccess(json.writeValueAsString(response), MediaType.APPLICATION_JSON));
    assertThat(client.refund(submission).get("vnp_TransactionNo")).isEqualTo("123456"); server.verify();
  }
  @ParameterizedTest @ValueSource(strings = {"vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_Command", "vnp_TransactionType"})
  void signedButMismatchedResultIsRejected(String field) throws Exception {
    var response = response(); response.put(field, field.equals("vnp_Amount") ? "1" : "WRONG"); sign(response);
    server.expect(requestTo(config.getQueryUrl())).andRespond(withSuccess(json.writeValueAsString(response), MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.refund(submission)).isInstanceOf(IllegalStateException.class);
  }
  @Test void tamperedResponseIsRejected() throws Exception {
    var response = response(); sign(response); response.put("vnp_TransactionStatus", "05");
    server.expect(requestTo(config.getQueryUrl())).andRespond(withSuccess(json.writeValueAsString(response), MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client.refund(submission)).isInstanceOf(IllegalStateException.class);
  }
  @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.5", "10000000000"})
  void invalidAmountsNeverProducePayload(String value) {
    assertThatThrownBy(() -> VnPayUtil.gatewayAmount(new BigDecimal(value)))
        .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
  }
  @Test void maximumWholeVndAmountFitsPublishedTwelveDigits() {
    assertThat(VnPayUtil.gatewayAmount(new BigDecimal("9999999999"))).isEqualTo("999999999900");
  }
}
