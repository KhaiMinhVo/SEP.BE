package com.influencermatch.backend.relationship;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import java.net.SocketTimeoutException;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ShortlistCrawlerClientTest {
  @ParameterizedTest
  @ValueSource(ints = {200, 202, 500, 408})
  void acceptedResponseAndFailuresUseFixedEndpoint(int code) {
    var builder = RestClient.builder().baseUrl("https://crawler.internal/api/v1/crawl");
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = new CrawlerServiceClient(builder.build());
    var creator = UUID.randomUUID();
    var campaign = UUID.randomUUID();
    var expected =
        server
            .expect(requestTo("https://crawler.internal/api/v1/crawl/shortlist-trigger"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.platform").value("TIKTOK"))
            .andExpect(jsonPath("$.creatorId").value(creator.toString()));
    if (code == 408) expected.andRespond(withException(new SocketTimeoutException("timeout")));
    else expected.andRespond(withStatus(HttpStatus.valueOf(code)));
    if (code < 300)
      assertThatCode(() -> client.requestShortlistCrawl("tiktok", "channel", creator, campaign))
          .doesNotThrowAnyException();
    else
      assertThatThrownBy(() -> client.requestShortlistCrawl("tiktok", "channel", creator, campaign))
          .isInstanceOf(Exception.class);
    server.verify();
  }
}
