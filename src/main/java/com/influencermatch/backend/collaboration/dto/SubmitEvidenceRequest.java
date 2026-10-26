package com.influencermatch.backend.collaboration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SubmitEvidenceRequest(
    @NotBlank
    @Size(max = 50)
    @Schema(
        description = "Type of evidence: POST_LINK, INSIGHTS_SCREENSHOT, DRAFT_VIDEO, or OTHER",
        example = "POST_LINK")
    String assetType,

    @NotBlank
    @Size(max = 1000)
    @Pattern(regexp = "^https?://.+", message = "storageUrl must be a valid HTTP or HTTPS URL")
    @Schema(
        description = "Storage or public URL for the evidence asset",
        example = "https://www.tiktok.com/@creator/video/123456789")
    String storageUrl,

    @Pattern(regexp = "^https?://.+", message = "publishedContentUrl must be a valid HTTP or HTTPS URL")
    @Size(max = 1000)
    @Schema(
        description = "Public post URL to update on collaboration if applicable",
        example = "https://www.tiktok.com/@creator/video/123456789")
    String publishedContentUrl,

    @Size(max = 500)
    @Schema(
        description = "Optional note or comment regarding the evidence",
        example = "Post published on TikTok with brand hashtag")
    String note) {
  public SubmitEvidenceRequest {
    assetType = assetType == null ? null : assetType.trim().toUpperCase();
    storageUrl = storageUrl == null ? null : storageUrl.trim();
    publishedContentUrl =
        publishedContentUrl == null || publishedContentUrl.isBlank()
            ? null
            : publishedContentUrl.trim();
    note = note == null || note.isBlank() ? null : note.trim();
  }
}
