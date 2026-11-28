package com.influencermatch.backend.recommendation.research.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class ResearchJsonMapper {

  private static final ObjectMapper MAPPER = JsonMapper.builder()
      .addModule(new JavaTimeModule())
      .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();

  private ResearchJsonMapper() {}

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static String toDeterministicJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize research contract", e);
    }
  }

  public static <T> T fromJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid research contract JSON for " + type.getSimpleName() + ": " + e.getMessage(), e);
    }
  }
}
