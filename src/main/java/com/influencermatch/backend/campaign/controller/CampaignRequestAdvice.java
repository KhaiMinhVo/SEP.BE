package com.influencermatch.backend.campaign.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.campaign.dto.CampaignRequest;
import com.influencermatch.backend.exception.ValidationException;
import java.io.*;
import java.lang.reflect.Type;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Detect presence, including explicit null, of retired request fields. */
@RestControllerAdvice(assignableTypes = CampaignController.class)
@RequiredArgsConstructor
public class CampaignRequestAdvice extends RequestBodyAdviceAdapter {
  private final ObjectMapper json;

  @Override
  public boolean supports(
      MethodParameter p, Type target, Class<? extends HttpMessageConverter<?>> converter) {
    return target == CampaignRequest.class;
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      HttpInputMessage input,
      MethodParameter p,
      Type target,
      Class<? extends HttpMessageConverter<?>> converter)
      throws IOException {
    byte[] bytes = input.getBody().readAllBytes();
    var node = json.readTree(bytes);
    if (node != null && (node.has("budgetMin") || node.has("budgetMax")))
      throw new ValidationException(
          "budgetMin/budgetMax are legacy read-only fields; use totalBudget and"
              + " creatorFeeMin/creatorFeeMax");
    return new HttpInputMessage() {
      public InputStream getBody() {
        return new ByteArrayInputStream(bytes);
      }

      public HttpHeaders getHeaders() {
        return input.getHeaders();
      }
    };
  }
}
