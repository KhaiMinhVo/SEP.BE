package com.influencermatch.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("dev")
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class AdminBootstrapConfiguration {
}
