package com.influencermatch.backend.creator.email;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.influencermatch.backend.support.PostgresApiTestSupport;
import com.influencermatch.backend.auth.service.VerificationEmailSender;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties="application.claim-email.encryption-key=")
class ClaimEmailConfigurationPostgresIntegrationTest extends PostgresApiTestSupport {
  @MockBean VerificationEmailSender sender;
  @Autowired CreatorRepository creators;
  @Autowired CreatorInvitationRepository invitations;
  @Test void missingEncryptionKeyReturns503BeforeAnyInvitationIsCreated() throws Exception {
    var brand=account(Role.BRAND);createBrand(brand);
    var channel=creators.saveAndFlush(Creator.builder().platform("TIKTOK").externalId(UUID.randomUUID().toString()).userName("missing-key").build());
    var error=call(brand,post("/creators/"+channel.getId()+"/invite"),Map.of("email","email@example.test"),503);
    assertThat(error.path("code").asText()).isEqualTo("EMAIL_DELIVERY_UNAVAILABLE");
    assertThat(invitations.findByTargetCreatorId(channel.getId())).isEmpty();
    verify(sender,never()).sendCreatorClaimInvitation(anyString(),anyString());
  }
}
