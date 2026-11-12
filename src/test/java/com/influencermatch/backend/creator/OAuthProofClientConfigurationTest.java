package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import com.influencermatch.backend.creator.client.OAuthProofClient;
import com.influencermatch.backend.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class OAuthProofClientConfigurationTest {
  @ParameterizedTest @ValueSource(strings={"prod","dev,prod","test",""})
  void simulatedProofIsUnavailableOutsideExclusiveDev(String profiles) {
    var env=new MockEnvironment();env.setActiveProfiles(profiles.isEmpty()?new String[0]:profiles.split(","));
    var client=new OAuthProofClient(env,true);
    assertThatThrownBy(()->client.exchangeAndVerify("TIKTOK","anything","target","creator"))
        .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.SOCIAL_PROOF_UNAVAILABLE));
  }
  @Test void devMustExplicitlyEnableProofSimulation() {
    var env=new MockEnvironment();env.setActiveProfiles("dev");
    assertThatThrownBy(()->new OAuthProofClient(env,false).exchangeAndVerify("TIKTOK","anything","id","name"))
        .isInstanceOf(BusinessException.class);
    assertThat(new OAuthProofClient(env,true).exchangeAndVerify("TIKTOK","sim:id:name","id","name").getProviderUserId()).isEqualTo("id");
  }
}
