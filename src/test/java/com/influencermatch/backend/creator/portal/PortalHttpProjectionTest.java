package com.influencermatch.backend.creator.portal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.common.dto.ApiResponse;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.mock.web.*;

class PortalHttpProjectionTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @SuppressWarnings("unchecked")
  final ObjectProvider<CreatorPortalProjection> provider = mock(ObjectProvider.class);

  Object render(String path, Object body, MockHttpServletResponse response) {
    return new PortalHttpProjection(provider, json)
        .beforeBodyWrite(
            body,
            null,
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter.class,
            new ServletServerHttpRequest(new MockHttpServletRequest("GET", path)),
            new ServletServerHttpResponse(response));
  }

  @Test
  void identicalCardsAreBothEnrichedWithOneBatchCall() {
    UUID id = UUID.randomUUID();
    var service = mock(CreatorPortalProjection.class);
    when(provider.getObject()).thenReturn(service);
    var v = new PortalDtos.Verification("LEGACY_UNVERIFIED", null, null, "NONE", "LEGACY");
    when(service.project(anyCollection(), eq(false)))
        .thenReturn(
            Map.of(id, new PortalDtos.Projection(UUID.randomUUID(), v, null, "NOT_STORED")));
    var card = Map.of("creatorId", id, "platform", "TIKTOK", "verifiedByInfluencerMatch", true);
    var response = new MockHttpServletResponse();
    JsonNode result =
        (JsonNode) render("/api/v1/creators", ApiResponse.ok(List.of(card, card)), response);
    assertThat(result.at("/data/0/verifiedByInfluencerMatch").asBoolean()).isFalse();
    assertThat(result.at("/data/1/verifiedByInfluencerMatch").asBoolean()).isFalse();
    verify(service, times(1)).project(anyCollection(), eq(false));
  }

  @Test
  void privateProfileAndOtherSubsystemResponsesAreNeverRewritten() {
    var body = ApiResponse.ok(Map.of("bookingEmail", "private@example.test"));
    assertThat(render("/api/v1/creators/portal/profile", body, new MockHttpServletResponse()))
        .isSameAs(body);
    assertThat(render("/api/v1/auth/me", body, new MockHttpServletResponse())).isSameAs(body);
    verifyNoInteractions(provider);
  }

  @Test
  void missingChannelCannotKeepAnOldVerifiedFlag() {
    var service = mock(CreatorPortalProjection.class);
    when(provider.getObject()).thenReturn(service);
    when(service.project(anyCollection(), eq(true))).thenReturn(Map.of());
    UUID id = UUID.randomUUID();
    JsonNode result =
        (JsonNode)
            render(
                "/creators/" + id,
                ApiResponse.ok(
                    Map.of("id", id, "platform", "TIKTOK", "verifiedByInfluencerMatch", true)),
                new MockHttpServletResponse());
    assertThat(result.at("/data/verifiedByInfluencerMatch").asBoolean()).isFalse();
    assertThat(result.at("/data/declaredProfile").isNull()).isTrue();
  }
}
