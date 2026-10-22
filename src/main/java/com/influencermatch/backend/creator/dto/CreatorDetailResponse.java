package com.influencermatch.backend.creator.dto;

import com.influencermatch.backend.creator.enums.CreatorSourceType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatorDetailResponse {
    private UUID id;
    private String platform;
    private String externalId;
    private String userName;
    private String displayName;
    private String profileUrl;
    private String avatarUrl;
    private String location;
    private CreatorSourceType sourceType;
    private boolean verifiedByInfluencerMatch;
    private LocalDateTime authorizedAt;

    // Metrics
    private Long followers;
    private BigDecimal avgViews;
    private BigDecimal avgLikes;
    private BigDecimal avgComments;
    private BigDecimal avgShares;
    private BigDecimal engagementRate;
    private String niche;
    private String category;
    private String contentSummary;

    // Recent Posts
    private List<PostDto> recentPosts;
}
