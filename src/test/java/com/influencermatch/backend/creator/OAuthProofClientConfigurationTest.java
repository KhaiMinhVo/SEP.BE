package com.influencermatch.backend.creator;
import static org.assertj.core.api.Assertions.*;
import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
class OAuthProofClientConfigurationTest {
  @Test void simulatedProofIsDisabledByDefaultAndInProduction() {
    var client=new OAuthProofClient();
    var env=new MockEnvironment(); env.setActiveProfiles("dev");
    ReflectionTestUtils.setField(client,"environment",env);
    assertUnavailable(client);
    ReflectionTestUtils.setField(client,"simulationEnabled",true);
    env.setActiveProfiles("dev","production"); assertUnavailable(client);
    env.setActiveProfiles("dev","prod"); assertUnavailable(client);
    env.setActiveProfiles("dev");
    assertThat(client.exchangeAndVerify("TIKTOK","sim:123:creator","123","creator").getProviderUserId()).isEqualTo("123");
  }
  void assertUnavailable(OAuthProofClient client) {
    assertThatThrownBy(() -> client.exchangeAndVerify("TIKTOK","code","123","creator"))
        .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE));
  }
}
