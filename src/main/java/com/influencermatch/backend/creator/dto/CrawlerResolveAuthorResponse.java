package com.influencermatch.backend.creator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CrawlerResolveAuthorResponse {

  private String status;
  private String platform;

  @JsonProperty("input_username")
  private String inputUsername;

  @JsonProperty("resolved_username")
  private String resolvedUsername;

  @JsonProperty("author_id")
  private String authorId;

  @JsonProperty("id_type")
  private String idType;

  @JsonProperty("observed_at")
  private java.time.Instant observedAt;

  @JsonProperty("sec_uid")
  private String secUid;

  @JsonProperty("expected_external_id")
  private String expectedExternalId;

  @JsonProperty("is_matched")
  private Boolean isMatched;

  @JsonProperty("previous_username")
  private String previousUsername;

  @JsonProperty("creator_info")
  private CreatorInfo creatorInfo;

  private String message;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class CreatorInfo {
    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    private Long followers;
    private Long following;

    @JsonProperty("heart_count")
    private Long heartCount;

    @JsonProperty("video_count")
    private Long videoCount;

    private String bio;

    @JsonProperty("bio_link")
    private String bioLink;

    @JsonProperty("is_verified")
    private Boolean isVerified;

    @JsonProperty("profile_url")
    private String profileUrl;
  }
}

