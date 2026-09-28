package com.influencermatch.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "application.oauth2.google")
public class GoogleOAuthProperties {
    private boolean enabled;
    private String clientId = "";
    private String clientSecret = "";
    private String frontendCallbackUrl = "http://localhost:5173/auth/google/callback";
    private long exchangeCodeTtlSeconds = 60;

    public void validateWhenEnabled() {
        if (!enabled) return;
        if (clientId == null || clientId.isBlank()) throw new IllegalStateException("GOOGLE_CLIENT_ID is required when Google OAuth is enabled");
        if (clientSecret == null || clientSecret.isBlank()) throw new IllegalStateException("GOOGLE_CLIENT_SECRET is required when Google OAuth is enabled");
        if (frontendCallbackUrl == null || !(frontendCallbackUrl.startsWith("http://") || frontendCallbackUrl.startsWith("https://")))
            throw new IllegalStateException("GOOGLE_FRONTEND_CALLBACK_URL must be an absolute HTTP(S) URL");
        if (exchangeCodeTtlSeconds < 30 || exchangeCodeTtlSeconds > 300)
            throw new IllegalStateException("Google exchange code TTL must be between 30 and 300 seconds");
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getFrontendCallbackUrl() { return frontendCallbackUrl; }
    public void setFrontendCallbackUrl(String frontendCallbackUrl) { this.frontendCallbackUrl = frontendCallbackUrl; }
    public long getExchangeCodeTtlSeconds() { return exchangeCodeTtlSeconds; }
    public void setExchangeCodeTtlSeconds(long exchangeCodeTtlSeconds) { this.exchangeCodeTtlSeconds = exchangeCodeTtlSeconds; }
}
