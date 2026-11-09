package com.influencermatch.backend.brand.service;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.brand.model.BrandProfile;
import java.util.*;

/** Canonical semantic input. No contact data, account IDs, or inferred preferences. */
public final class BrandContextData {
  public static final List<String> FIELDS =
      List.of(
          "industry",
          "productCategories",
          "targetMarkets",
          "targetAudiences",
          "brandTone",
          "preferredPlatforms",
          "description",
          "positioning",
          "guidelines");

  private BrandContextData() {}

  public static String text(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  public static List<String> list(List<String> values) {
    return values == null
        ? List.of()
        : values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .sorted()
            .toList();
  }

  public static JsonNode of(BrandProfile p, ObjectMapper mapper) {
    Map<String, Object> data = new TreeMap<>();
    data.put("industry", text(p.getIndustry()));
    data.put("productCategories", list(p.getProductCategories()));
    data.put("targetMarkets", list(p.getTargetMarkets()));
    data.put("targetAudiences", list(p.getTargetAudiences()));
    data.put("brandTone", text(p.getBrandTone()));
    data.put("preferredPlatforms", list(p.getPreferredPlatforms()));
    data.put("description", text(p.getDescription()));
    data.put("positioning", text(p.getPositioning()));
    data.put("guidelines", text(p.getGuidelines()));
    return mapper.valueToTree(data);
  }

  public static String embeddingText(JsonNode context) {
    var parts = new ArrayList<String>();
    for (String field : FIELDS) {
      var value = context.path(field);
      if (value.isArray()) {
        List<String> items = new ArrayList<>();
        value.forEach(
            v -> {
              if (v.isTextual() && !v.asText().isBlank()) items.add(v.asText());
            });
        if (!items.isEmpty()) parts.add(field + ": " + String.join(", ", items));
      } else if (value.isTextual() && !value.asText().isBlank())
        parts.add(field + ": " + value.asText());
    }
    return String.join("\n", parts);
  }
}
