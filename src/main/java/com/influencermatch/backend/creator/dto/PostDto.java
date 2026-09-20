package com.influencermatch.backend.creator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostDto {
  private String platformPostId;
  private String postUrl;
  private String caption;
  private Long views;
  private Long likes;
  private Long comments;
  private Long shares;
  private String postedAt;
}
