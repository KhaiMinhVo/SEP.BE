package com.influencermatch.backend.config;

import com.influencermatch.backend.security.apikey.CreatorServiceKeyFilter;
import com.influencermatch.backend.security.jwt.JwtAccessDeniedHandler;
import com.influencermatch.backend.security.jwt.JwtAuthenticationEntryPoint;
import com.influencermatch.backend.security.jwt.JwtAuthenticationFilter;



import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
  private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
  private final UserDetailsService userDetailsService;

  // Patterns are relative to context-path /api/v1
  private static final String[] PUBLIC_ENDPOINTS = {
    "/integrations/meta/callback",
    "/integrations/instagram/callback",
    "/auth/register",
    "/auth/login",
    "/auth/refresh",
    "/auth/verify-email",
    "/auth/resend-verification",
    "/auth/creator/register",
    "/auth/creator-invitations/resolve",
    "/auth/password-setup/request",
    "/auth/password-setup/confirm",
    "/actuator/health",
    "/v3/api-docs/**",
    "/api-docs/**",
    "/swagger-ui/**",
    "/swagger-ui.html",
    "/webjars/**",
    "/billing/vnpay/return",
    "/billing/vnpay/ipn",
    "/creators/claim/invitation/verify"
  };

  @Bean
  @org.springframework.core.annotation.Order(1)
  public SecurityFilterChain ingestionFilterChain(
      HttpSecurity http,
      @org.springframework.beans.factory.annotation.Value("${CREATOR_INGESTION_API_KEY:}")
          String key,
      com.fasterxml.jackson.databind.ObjectMapper mapper)
      throws Exception {
    http.securityMatcher("/creators/ingest")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .securityContext(
            c ->
                c.securityContextRepository(
                    new org.springframework.security.web.context.NullSecurityContextRepository()))
        .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("SERVICE_INGEST_CREATOR"))
        .addFilterBefore(
            new CreatorServiceKeyFilter(key, mapper),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  @org.springframework.core.annotation.Order(2)
  public SecurityFilterChain securityFilterChain(HttpSecurity http,
      org.springframework.beans.factory.ObjectProvider<com.influencermatch.backend.auth.service.DevCreatorLoginService> devCreatorLogin) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .securityContext(
            c ->
                c.securityContextRepository(
                    new org.springframework.security.web.context.NullSecurityContextRepository()))
        .requestCache(
            c ->
                c.requestCache(
                    new org.springframework.security.web.savedrequest.NullRequestCache()))
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(jwtAuthenticationEntryPoint)
                    .accessDeniedHandler(jwtAccessDeniedHandler))
        .sessionManagement(
            s ->
                s.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth -> {
                if (devCreatorLogin.getIfAvailable() != null) {
                  auth.requestMatchers(org.springframework.http.HttpMethod.POST, "/auth/dev/creator-login").permitAll();
                }
                auth.requestMatchers(org.springframework.http.HttpMethod.POST,"/admin/creator-conflicts/*/resolve").authenticated();
                auth.requestMatchers(PUBLIC_ENDPOINTS)
                    .permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/admin/data-quality/pending")
                    .hasAuthority("REVIEW_CREATOR_CLASSIFICATION")
                    .requestMatchers(org.springframework.http.HttpMethod.PUT, "/admin/data-quality/{id}/approve")
                    .hasAuthority("REVIEW_CREATOR_CLASSIFICATION")
                    .requestMatchers("/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/auth/me", "/auth/logout")
                    .authenticated()
                    .anyRequest()
                    .authenticated(); })
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  @Bean
  public AuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
    provider.setUserDetailsService(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder());
    return provider;
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
      throws Exception {
    return config.getAuthenticationManager();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
