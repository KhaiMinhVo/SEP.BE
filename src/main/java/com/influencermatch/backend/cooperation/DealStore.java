package com.influencermatch.backend.cooperation;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.exception.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DealStore {
  final JdbcTemplate db;
  final ObjectMapper json;
  final Clock clock;

  public Instant now() {
    return clock.instant();
  }

  public java.sql.Timestamp time() {
    return java.sql.Timestamp.from(now());
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid cooperation data");
    }
  }

  public JsonNode decode(Object value) {
    try {
      return value == null ? json.nullNode() : json.readTree(value.toString());
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored cooperation data");
    }
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new NotFoundException("Cooperation resource not found");
    return rows.getFirst();
  }

  public Optional<Map<String, Object>> optional(String sql, Object... args) {
    return db.queryForList(sql, args).stream().findFirst();
  }

  public UUID id(Map<String, Object> row, String key) {
    return (UUID) row.get(key);
  }

  public long revision(Map<String, Object> row) {
    return ((Number) row.get("revision")).longValue();
  }

  public Map<String, Object> deal(UUID id) {
    return one("select * from cooperation_deal where id=?", id);
  }

  public List<Map<String, Object>> members(UUID id) {
    return db.queryForList(
        "select m.*,c.master_id,c.platform,c.source_type,c.user_name from cooperation_member m join"
            + " creator c on c.id=m.creator_id where m.deal_id=? order by m.creator_id",
        id);
  }

  public Map<String, Object> latest(UUID deal) {
    return optional(
            "select * from cooperation_proposal where deal_id=? order by revision desc limit 1",
            deal)
        .orElseThrow(
            () -> new BusinessException(ErrorCode.DEAL_STATE_CONFLICT, "A proposal is required"));
  }

  public static BusinessException conflict(String message) {
    return new BusinessException(ErrorCode.DEAL_STATE_CONFLICT, message);
  }

  public static UUID uuid(Object id) {
    return (UUID) id;
  }
}
