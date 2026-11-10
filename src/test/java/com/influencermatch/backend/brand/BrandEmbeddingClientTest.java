package com.influencermatch.backend.brand;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.creator.client.AiServiceClient;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BrandEmbeddingClientTest {
  @ParameterizedTest
  @ValueSource(
      strings = {"valid", "model", "dimensions", "zero", "string", "missing", "http", "timeout"})
  void validatesStrictProviderContract(String mode) throws Exception {
    var builder = RestClient.builder().baseUrl("https://ai.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = new AiServiceClient("https://ai.test");
    org.springframework.test.util.ReflectionTestUtils.setField(
        client, "restClient", builder.build());
    var body =
        new ObjectMapper()
            .createObjectNode()
            .put("status", "success")
            .put("model", mode.equals("model") ? "other" : "expected")
            .put("dimensions", mode.equals("dimensions") ? 384 : 768);
    var vector = body.putArray("vector");
    for (int i = 0; i < 768; i++) vector.add(mode.equals("zero") ? 0 : 0.1);
    if (mode.equals("string"))
      vector.set(0, new ObjectMapper().getNodeFactory().textNode("secret"));
    if (mode.equals("missing")) body.remove("status");
    var expected = server.expect(requestTo("https://ai.test/embed"));
    if (mode.equals("http")) expected.andRespond(withServerError());
    else if (mode.equals("timeout"))
      expected.andRespond(withException(new java.net.SocketTimeoutException()));
    else expected.andRespond(withSuccess(body.toString(), MediaType.APPLICATION_JSON));
    if (mode.equals("valid"))
      assertThat(client.getBrandEmbedding("Brand context", "expected")).hasSize(768);
    else
      assertThatThrownBy(() -> client.getBrandEmbedding("Brand context", "expected"))
          .isInstanceOf(RuntimeException.class);
    server.verify();
  }
}
