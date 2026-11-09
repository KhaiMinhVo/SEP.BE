package com.influencermatch.backend.creator.tiktok;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TikTokLoginProperties.class)
public class TikTokLoginConfig {}
