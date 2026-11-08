package com.influencermatch.backend.campaign.service;

import com.fasterxml.jackson.databind.*;
import com.influencermatch.backend.campaign.model.Campaign;
import java.util.*;

public final class CampaignBriefData {
  private CampaignBriefData() {}

  public static JsonNode snapshot(Campaign c, ObjectMapper mapper) {
    Map<String, Object> v = new TreeMap<>();
    v.put("name", c.getName());
    v.put("productService", c.getProductService());
    v.put("objective", c.getObjective());
    v.put("brief", c.getBrief());
    v.put("platforms", c.getPlatforms());
    v.put("niches", c.getNiches());
    v.put("locations", c.getLocations());
    v.put("targetAudiences", c.getTargetAudiences());
    v.put("contentType", c.getContentType());
    v.put("startDate", c.getStartDate());
    v.put("endDate", c.getEndDate());
    v.put("followerMin", c.getFollowerMin());
    v.put("followerMax", c.getFollowerMax());
    v.put("totalBudget", c.getTotalBudget());
    v.put("currency", c.getCurrency());
    v.put("creatorFeeMin", c.getCreatorFeeMin());
    v.put("creatorFeeMax", c.getCreatorFeeMax());
    v.put("creatorMode", c.getCreatorMode());
    v.put("targetCreatorCount", c.getTargetCreatorCount());
    v.put("deliverables", c.getDeliverables());
    v.put("budgetMin", c.getBudgetMin());
    v.put("budgetMax", c.getBudgetMax());
    return mapper.valueToTree(v);
  }

  public static String text(JsonNode data, JsonNode brand) {
    StringBuilder text = new StringBuilder();
    for (String f :
        List.of(
            "name",
            "productService",
            "objective",
            "brief",
            "platforms",
            "niches",
            "locations",
            "targetAudiences",
            "contentType",
            "startDate",
            "endDate",
            "totalBudget",
            "currency",
            "creatorFeeMin",
            "creatorFeeMax",
            "creatorMode",
            "targetCreatorCount",
            "deliverables")) {
      var v = data.path(f);
      if (!v.isNull() && !v.isMissingNode() && !(v.isArray() && v.isEmpty()))
        text.append(f).append(": ").append(v.isTextual() ? v.asText() : v.toString()).append("\n");
    }
    Map<String, String> fallback =
        Map.of(
            "niches",
            "productCategories",
            "locations",
            "targetMarkets",
            "targetAudiences",
            "targetAudiences",
            "platforms",
            "preferredPlatforms");
    for (var e : new TreeMap<>(fallback).entrySet())
      if (data.path(e.getKey()).isMissingNode()
          || data.path(e.getKey()).isNull()
          || data.path(e.getKey()).isEmpty()) append(text, brand, e.getValue());
    if (data.path("niches").isMissingNode() || data.path("niches").isEmpty())
      append(text, brand, "industry");
    for (String f : List.of("brandTone", "guidelines")) append(text, brand, f);
    if (data.path("productService").asText("").isBlank())
      for (String f : List.of("description", "positioning")) append(text, brand, f);
    return text.toString().trim();
  }

  private static void append(StringBuilder out, JsonNode brand, String field) {
    var v = brand.path(field);
    if (!v.isMissingNode() && !v.isNull() && !(v.isArray() && v.isEmpty()))
      out.append("Brand ")
          .append(field)
          .append(": ")
          .append(v.isTextual() ? v.asText() : v.toString())
          .append("\n");
  }
}
