package com.influencermatch.backend.creator.portal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.influencermatch.backend.exception.ValidationException;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;

/** Strict PATCH parsing. No URLs are fetched and contact values never enter error messages. */
public final class PortalData {
  private PortalData() {}

  public static ObjectNode empty(ObjectMapper j) {
    return (ObjectNode)
        j.valueToTree(
            new PortalDtos.Data(
                null,
                List.of(),
                List.of(),
                List.of(),
                new PortalDtos.Availability(null, null, null),
                new PortalDtos.Sharing(true, true, true, true, false),
                new PortalDtos.Contacts(
                    new PortalDtos.Contact(null, PortalDtos.Visibility.PRIVATE),
                    new PortalDtos.Contact(null, PortalDtos.Visibility.PRIVATE),
                    new PortalDtos.Contact(null, PortalDtos.Visibility.PRIVATE))));
  }

  static void keys(JsonNode n, String... allowed) {
    if (!n.isObject()) throw bad("Expected JSON object");
    var set = Set.of(allowed);
    n.fieldNames()
        .forEachRemaining(
            k -> {
              if (!set.contains(k)) throw bad("Unsupported profile field: " + k);
            });
  }

  static ValidationException bad(String message) {
    return new ValidationException(message);
  }

  static String text(JsonNode n, int max) {
    if (n == null || n.isNull()) return null;
    if (!n.isTextual()) throw bad("Expected text");
    String s = n.asText().trim();
    if (s.length() > max) throw bad("Profile text exceeds maximum length");
    return s.isEmpty() ? null : s;
  }

  static ArrayNode strings(ObjectMapper j, JsonNode n, int max) {
    var out = j.createArrayNode();
    if (n.isNull()) return out;
    if (!n.isArray() || n.size() > 20) throw bad("Expected at most 20 list values");
    var values = new TreeMap<String, String>();
    for (var v : n) {
      String t = text(v, max);
      if (t == null) throw bad("List values must not be blank");
      values.putIfAbsent(t.toLowerCase(Locale.ROOT), t);
    }
    values.values().forEach(out::add);
    return out;
  }

  static void merge(ObjectNode target, JsonNode patch) {
    patch.fields().forEachRemaining(e -> target.set(e.getKey(), e.getValue().deepCopy()));
  }

  public static ObjectNode patch(ObjectMapper j, ObjectNode before, JsonNode request) {
    keys(
        request,
        "introduction",
        "serviceAreas",
        "acceptedNiches",
        "portfolio",
        "availability",
        "sharing",
        "contacts");
    var out = before.deepCopy();
    if (request.has("introduction"))
      out.set("introduction", j.valueToTree(text(request.get("introduction"), 4000)));
    for (String key : List.of("serviceAreas", "acceptedNiches"))
      if (request.has(key))
        out.set(key, strings(j, request.get(key), key.equals("serviceAreas") ? 150 : 100));
    if (request.has("sharing")) {
      var p = request.get("sharing");
      keys(p, "introduction", "serviceAreas", "acceptedNiches", "portfolio", "availability");
      for (var it = p.fields(); it.hasNext(); ) {
        var e = it.next();
        if (!e.getValue().isBoolean()) throw bad("Sharing flags must be booleans");
      }
      merge((ObjectNode) out.get("sharing"), p);
    }
    if (request.has("availability")) {
      var p = request.get("availability");
      var a = (ObjectNode) out.get("availability");
      if (p.isNull()) a = (ObjectNode) empty(j).get("availability");
      else {
        keys(p, "status", "availableFrom", "note");
        merge(a, p);
      }
      String status = text(a.get("status"), 30);
      a.set("status", j.valueToTree(status));
      if (status != null)
        try {
          PortalDtos.AvailabilityStatus.valueOf(status);
        } catch (Exception e) {
          throw bad("Invalid availability status");
        }
      if (!a.path("availableFrom").isNull()) {
        if (!a.path("availableFrom").isTextual()) throw bad("Invalid availability date");
        try {
          LocalDate.parse(a.path("availableFrom").asText());
        } catch (Exception e) {
          throw bad("Invalid availability date");
        }
      }
      a.set("note", j.valueToTree(text(a.get("note"), 1000)));
      out.set("availability", a);
    }
    if (request.has("contacts")) {
      var p = request.get("contacts");
      keys(p, "bookingEmail", "zalo", "managerContact");
      for (String k : List.of("bookingEmail", "zalo", "managerContact"))
        if (p.has(k)) {
          JsonNode item = p.get(k);
          ObjectNode c = (ObjectNode) out.path("contacts").get(k);
          if (item.isNull()) c = (ObjectNode) empty(j).path("contacts").get(k);
          else {
            keys(item, "value", "visibility");
            merge(c, item);
          }
          String value = text(c.get("value"), k.equals("bookingEmail") ? 254 : 500);
          c.set("value", j.valueToTree(value));
          if (value != null
              && k.equals("bookingEmail")
              && !value.matches("[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+"))
            throw bad("Invalid booking email");
          if (!c.path("visibility").isTextual()) throw bad("Invalid contact visibility");
          try {
            PortalDtos.Visibility.valueOf(c.path("visibility").asText());
          } catch (Exception e) {
            throw bad("Invalid contact visibility");
          }
          ((ObjectNode) out.get("contacts")).set(k, c);
        }
    }
    if (request.has("portfolio")) {
      JsonNode p = request.get("portfolio");
      if (p.isNull()) p = j.createArrayNode();
      if (!p.isArray() || p.size() > 20) throw bad("Expected at most 20 portfolio items");
      var items = j.createArrayNode();
      Set<UUID> used = new HashSet<>();
      int index = 0;
      for (var item : p) {
        keys(item, "id", "title", "url", "description");
        String title = text(item.get("title"), 200),
            url = text(item.get("url"), 2000),
            description = text(item.get("description"), 1000);
        if (title == null || url == null) throw bad("Portfolio title and HTTPS URL are required");
        try {
          var u = URI.create(url);
          if (!"https".equalsIgnoreCase(u.getScheme())
              || u.getHost() == null
              || u.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (Exception e) {
          throw bad("Portfolio URL must be HTTPS without credentials");
        }
        UUID id = null;
        if (item.hasNonNull("id")) {
          try {
            id = UUID.fromString(item.get("id").asText());
          } catch (Exception e) {
            throw bad("Invalid portfolio item ID");
          }
          boolean own = false;
          for (var old : before.path("portfolio"))
            if (id.toString().equals(old.path("id").asText())) own = true;
          if (!own) throw bad("Portfolio item does not belong to this profile");
        } else if (index < before.path("portfolio").size()) {
          var old = before.path("portfolio").get(index);
          if (Objects.equals(title, text(old.get("title"), 200))
              && Objects.equals(url, text(old.get("url"), 2000))
              && Objects.equals(description, text(old.get("description"), 1000)))
            id = UUID.fromString(old.path("id").asText());
        }
        if (id == null) id = UUID.randomUUID();
        if (!used.add(id)) throw bad("Duplicate portfolio item ID");
        var v = j.createObjectNode().put("id", id.toString()).put("title", title).put("url", url);
        v.set("description", j.valueToTree(description));
        items.add(v);
        index++;
      }
      out.set("portfolio", items);
    }
    return out;
  }
}
