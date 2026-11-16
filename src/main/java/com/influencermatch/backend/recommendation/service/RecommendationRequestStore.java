package com.influencermatch.backend.recommendation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.exception.ConflictException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.recommendation.dto.RecommendationResponseDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecommendationRequestStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public RecommendationResponseDto replayOrClaim(UUID campaignId, String key, String requestHash) {
    int inserted =
        jdbc.update(
            "insert into ai_recommendation_request(campaign_id,idempotency_key,request_hash)"
                + " values(?,?,?) on conflict(campaign_id,idempotency_key) do nothing",
            campaignId,
            key,
            requestHash);
    if (inserted == 1) return null;
    return replay(campaignId, key, requestHash);
  }

  public RecommendationResponseDto replay(UUID campaignId, String key, String requestHash) {
    var rows =
        jdbc.query(
            "select request_hash,response::text from ai_recommendation_request where"
                + " campaign_id=? and idempotency_key=?",
            (r, n) -> new StoredRequest(r.getString(1), r.getString(2)),
            campaignId,
            key);
    if (rows.isEmpty()) return null;
    var stored = rows.getFirst();
    if (!requestHash.equals(stored.requestHash()))
      throw new ConflictException(
          ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency key has different content");
    if (stored.response() == null)
      throw new IllegalStateException("Recommendation request receipt has no response");
    try {
      return objectMapper.readValue(stored.response(), RecommendationResponseDto.class);
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid stored recommendation response", ex);
    }
  }

  public void complete(UUID campaignId, String key, String response) {
    int updated =
        jdbc.update(
            "update ai_recommendation_request set response=cast(? as jsonb) where campaign_id=?"
                + " and idempotency_key=? and response is null",
            response,
            campaignId,
            key);
    if (updated != 1) throw new IllegalStateException("Recommendation receipt was not claimable");
  }

  private record StoredRequest(String requestHash, String response) {}
}
