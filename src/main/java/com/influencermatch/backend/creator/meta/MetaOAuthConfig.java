package com.influencermatch.backend.creator.meta;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
  MetaOAuthProperties.class,
  InstagramOAuthProperties.class,
  InstagramUnifiedOAuthProperties.class
})
public class MetaOAuthConfig {}
