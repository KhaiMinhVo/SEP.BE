package com.influencermatch.backend.creator.portal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencermatch.backend.common.dto.ApiResponse;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Batches presentation-only profile/verification enrichment; never alters candidate scores. */
@RestControllerAdvice
@RequiredArgsConstructor
public class PortalHttpProjection implements ResponseBodyAdvice<Object> {
  final ObjectProvider<CreatorPortalProjection> projections;
  final ObjectMapper json;

  public boolean supports(
      MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
    return org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class
        .isAssignableFrom(converter);
  }

  public Object beforeBodyWrite(
      Object body,
      MethodParameter method,
      MediaType media,
      Class<? extends HttpMessageConverter<?>> converter,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    String path = request.getURI().getPath().replaceFirst("^/api/v1", "");
    boolean detail = path.matches("/creators/[^/]+"),
        accounts = path.equals("/creators/portal/accounts"),
        reach = path.equals("/creators/portal/accounts/reach"),
        compare = path.matches("/campaigns/[^/]+/shortlist/compare");
    boolean selected =
        detail
            || accounts
            || reach
            || compare
            || path.equals("/creators")
            || path.equals("/creators/search/semantic")
            || path.matches("/campaigns/[^/]+/discovery")
            || path.matches("/campaigns/[^/]+/shortlist(?:/items|/batch|/[^/]+)?");
    if (!selected || !(body instanceof ApiResponse<?>)) return body;
    ObjectNode result = json.valueToTree(body);
    var nodes = new IdentityHashMap<ObjectNode, UUID>();
    collect(
        result.path("data"),
        nodes,
        detail || accounts || path.equals("/creators/search/semantic"),
        reach);
    if (nodes.isEmpty()) return body;
    var values =
        projections.getObject().project(new LinkedHashSet<>(nodes.values()), detail || compare);
    nodes.forEach(
        (node, id) -> {
          var p = values.get(id);
          if (p == null) {
            node.put("verifiedByInfluencerMatch", false);
            node.set(
                "verification",
                json.valueToTree(
                    new PortalDtos.Verification("UNVERIFIED", null, null, "NONE", null)));
            node.putNull("declaredProfile");
            return;
          }
          node.set("verification", json.valueToTree(p.verification()));
          node.put("verifiedByInfluencerMatch", p.verification().verified());
          node.set("masterId", json.valueToTree(p.masterId()));
          node.set("declaredProfile", json.valueToTree(p.declaredProfile()));
          if (reach) {
            node.put("tokenStatus", p.tokenStatus());
            node.set("verificationMethod", json.valueToTree(p.verification().method()));
          }
        });
    response.getHeaders().setCacheControl("private, no-store");
    return result;
  }

  void collect(JsonNode node, Map<ObjectNode, UUID> out, boolean useId, boolean reach) {
    if (node.isArray()) {
      node.forEach(n -> collect(n, out, useId, reach));
      return;
    }
    if (!node.isObject()) return;
    String key = reach ? "channelId" : node.has("creatorId") ? "creatorId" : useId ? "id" : null;
    if (key != null && node.hasNonNull(key) && node.has("platform"))
      try {
        out.put((ObjectNode) node, UUID.fromString(node.path(key).asText()));
      } catch (IllegalArgumentException ignored) {
      }
    node.elements().forEachRemaining(n -> collect(n, out, false, reach));
  }
}
