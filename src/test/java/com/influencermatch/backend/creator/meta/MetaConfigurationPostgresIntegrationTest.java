package com.influencermatch.backend.creator.meta;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.user.enums.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "application.meta.enabled=false")
class MetaConfigurationPostgresIntegrationTest extends PostgresApiTestSupport {
  @Autowired MetaOAuthSessionRepository sessions;

  @Test
  void disabledMetaFailsClosedWithoutCreatingSessionOrAccount() throws Exception {
    long count = sessions.count();
    var error =
        call(
            account(Role.CREATOR),
            post("/creators/portal/accounts/connect/meta/start"),
            Map.of("platform", "FACEBOOK"),
            503);
    assertThat(error.path("code").asText()).isEqualTo("META_OAUTH_UNAVAILABLE");
    assertThat(sessions.count()).isEqualTo(count);
  }
}
