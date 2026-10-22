package com.influencermatch.backend.creator.dto;
import com.influencermatch.backend.creator.enums.*;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.URL;
import java.time.LocalDateTime;
import java.util.*;
public final class CreatorClaimDtos {
  private CreatorClaimDtos() {}
  public record ClaimRequest(
      @NotEmpty @Size(max=5) List<@NotBlank @Size(max=1000) @URL(regexp="(?i)^https?://.+") String> evidenceUrls,
      @NotBlank @Size(max=2000) String note) {
    public ClaimRequest { if(evidenceUrls!=null) evidenceUrls=evidenceUrls.stream().map(v->v==null?null:v.trim()).toList(); note=trim(note); }
  }
  public enum Decision { APPROVED, REJECTED }
  public record ReviewRequest(@NotNull Decision decision,@NotBlank @Size(max=2000) String reason) {
    public ReviewRequest { reason=trim(reason); }
  }
  public record RevokeRequest(@NotBlank @Size(max=2000) String reason) {
    public RevokeRequest { reason=trim(reason); }
  }
  public record ClaimResponse(UUID id,UUID creatorId,UUID claimantId,List<String> evidenceUrls,String note,
      CreatorClaimStatus status,UUID reviewerId,String reason,LocalDateTime reviewedAt,
      UUID revokedById,String revokeReason,LocalDateTime revokedAt,LocalDateTime createdAt,LocalDateTime updatedAt) {}
  public record PublicCreator(UUID id,String platform,String externalId,String userName,String displayName,
      String profileUrl,String avatarUrl,String location,CreatorSourceType sourceType,
      boolean verifiedByInfluencerMatch,LocalDateTime authorizedAt) {}
  private static String trim(String value) { return value==null?null:value.trim(); }
}
