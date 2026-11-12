package com.influencermatch.backend.collaboration.dto;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;

/** Prevent truncating decimal JSON counts and ratings to integers. */
public final class StrictNumbers {
  private StrictNumbers() {}

  public static class LongValue extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
      if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT))
        return (Long) context.handleUnexpectedToken(Long.class, parser);
      return parser.getLongValue();
    }
  }

  public static class IntegerValue extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT))
        return (Integer) context.handleUnexpectedToken(Integer.class, parser);
      return parser.getIntValue();
    }
  }

  public static class BooleanValue extends JsonDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      if (!parser.hasToken(JsonToken.VALUE_TRUE) && !parser.hasToken(JsonToken.VALUE_FALSE))
        return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
      return parser.getBooleanValue();
    }
  }
}
