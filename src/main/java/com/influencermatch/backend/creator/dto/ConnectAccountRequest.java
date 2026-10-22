package com.influencermatch.backend.creator.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectAccountRequest {

  @NotBlank(message = "Authorization code is required")
  @JsonAlias({"authorizationCode", "authCode"})
  private String code;

  private String redirectUri;
}
