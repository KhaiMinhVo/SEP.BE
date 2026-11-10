package com.influencermatch.backend.campaign.history;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/**
 * Whitelists semantic Campaign input, never copying feedback, embeddings or recipient identities.
 */
public final class HistoryBrief {
  private HistoryBrief() {}

  public static final List<String> FIELDS =
      List.of(
          "name",
          "productService",
          "objective",
          "brief",
          "targetAudiences",
          "contentType",
          "startDate",
          "endDate",
          "platforms",
          "niches",
          "locations",
          "followerMin",
          "followerMax",
          "totalBudget",
          "currency",
          "creatorFeeMin",
          "creatorFeeMax",
          "creatorMode",
          "targetCreatorCount",
          "deliverables");

  public static ObjectNode clean(ObjectMapper json, JsonNode source) {
    var out = json.createObjectNode();
    for (String field : FIELDS)
      out.set(field, source.has(field) ? source.get(field).deepCopy() : json.nullNode());
    // Only generic campaign deliverable fields survive, even for malformed historical JSON.
    if (out.path("deliverables").isArray()) {
      var items = json.createArrayNode();
      for (var d : out.path("deliverables")) {
        var item = json.createObjectNode();
        for (String f : List.of("platform", "contentType", "quantity"))
          item.set(f, d.has(f) ? d.get(f).deepCopy() : json.nullNode());
        items.add(item);
      }
      out.set("deliverables", items);
    }
    return out;
  }

  public static List<String> missing(JsonNode source) {
    return FIELDS.stream().filter(f -> !source.hasNonNull(f)).toList();
  }

  public static JsonNode canonical(ObjectMapper json, JsonNode node) {
    if (node.isObject()) {
      var out = json.createObjectNode();
      var names = new TreeSet<String>();
      node.fieldNames().forEachRemaining(names::add);
      for (var key : names) out.set(key, canonical(json, node.get(key)));
      return out;
    }
    if (node.isArray()) {
      var out = json.createArrayNode();
      node.forEach(v -> out.add(canonical(json, v)));
      return out;
    }
    return node;
  }
}
