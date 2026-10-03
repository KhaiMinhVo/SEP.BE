package com.influencermatch.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.exception.ErrorCode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class CreatorServiceKeyFilter extends OncePerRequestFilter {
  private final String configuredKey;
  private final ObjectMapper mapper;

  public CreatorServiceKeyFilter(String configuredKey, ObjectMapper mapper) {
    this.configuredKey = configuredKey;
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    ErrorCode failure = null;
    String key = req.getHeader("X-Service-Key");
    if (configuredKey == null || configuredKey.isBlank())
      failure = ErrorCode.INGESTION_NOT_CONFIGURED;
    else if (key == null || !MessageDigest.isEqual(digest(key), digest(configuredKey)))
      failure = ErrorCode.UNAUTHENTICATED;
    if (failure != null) {
      ProblemDetail problem = ProblemDetail.forStatusAndDetail(failure.status(), failure.title());
      problem.setTitle(failure.title());
      problem.setProperty("code", failure.name());
      problem.setProperty("traceId", org.slf4j.MDC.get("traceId"));
      problem.setInstance(java.net.URI.create(req.getRequestURI()));
      res.setStatus(failure.status().value());
      res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      mapper.writeValue(res.getOutputStream(), problem);
      return;
    }
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "creator-service",
                null,
                List.of(new SimpleGrantedAuthority("SERVICE_INGEST_CREATOR"))));
    try {
      chain.doFilter(req, res);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
