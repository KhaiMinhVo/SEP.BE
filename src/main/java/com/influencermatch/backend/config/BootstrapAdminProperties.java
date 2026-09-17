package com.influencermatch.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "application.bootstrap-admin")
public class BootstrapAdminProperties {
    private boolean enabled;
    private String email = "";
    private String password = "";
    private String fullName = "System Administrator";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public void validateWhenEnabled() {
        if (!enabled) return;
        if (email == null || !email.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL must be a valid email address");
        }
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must contain at least 12 characters");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_FULL_NAME must not be blank");
        }
    }
}


