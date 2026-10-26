package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.auth.controller.DevCreatorLoginController;
import com.influencermatch.backend.auth.service.*;
import com.influencermatch.backend.auth.repository.ExternalIdentityRepository;
import com.influencermatch.backend.security.jwt.JwtTokenProvider;
import com.influencermatch.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DevCreatorLoginConfigurationTest {
  private final ApplicationContextRunner runner=new ApplicationContextRunner()
      .withPropertyValues("application.security.jwt.expiration=900000",
          "application.security.jwt.refresh-token.expiration=604800000")
      .withUserConfiguration(DevCreatorLoginController.class,DevCreatorLoginService.class)
      .withBean(com.influencermatch.backend.creator.repository.CreatorMasterRepository.class,()->mock(com.influencermatch.backend.creator.repository.CreatorMasterRepository.class))
      .withBean(UserRepository.class,()->mock(UserRepository.class))
      .withBean(ExternalIdentityRepository.class,()->mock(ExternalIdentityRepository.class))
      .withBean(JwtTokenProvider.class,()->mock(JwtTokenProvider.class))
      .withBean(com.influencermatch.backend.security.permission.service.EffectivePermissionService.class,
          ()->mock(com.influencermatch.backend.security.permission.service.EffectivePermissionService.class))
      .withBean(RefreshTokenService.class,()->mock(RefreshTokenService.class));

  @Test void onlyEnabledInExplicitDevWithFlag() {
    runner.withPropertyValues("spring.profiles.active=dev")
        .run(c->assertThat(c).doesNotHaveBean(DevCreatorLoginController.class).doesNotHaveBean(DevCreatorLoginService.class));
    for(String profiles:new String[]{"prod","dev,prod","test"}) {
      runner.withPropertyValues("spring.profiles.active="+profiles,"application.auth.creator-mock.enabled=true")
          .run(c->assertThat(c).doesNotHaveBean(DevCreatorLoginController.class).doesNotHaveBean(DevCreatorLoginService.class));
    }
    runner.withPropertyValues("spring.profiles.active=dev","application.auth.creator-mock.enabled=false")
        .run(c->assertThat(c).doesNotHaveBean(DevCreatorLoginController.class));
    runner.withPropertyValues("spring.profiles.active=dev","application.auth.creator-mock.enabled=true")
        .run(c->assertThat(c).hasSingleBean(DevCreatorLoginController.class).hasSingleBean(DevCreatorLoginService.class));
  }
}
