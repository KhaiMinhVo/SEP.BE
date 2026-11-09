package com.influencermatch.backend.recommendation.learning;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LearningKpisTest {
  final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  final UUID channel = UUID.randomUUID(), unit = UUID.randomUUID();
  final Instant start = Instant.parse("2026-11-01T00:00:00Z"), end = start.plusSeconds(86400);

  LearningKpis.Target target(String metric) {
    return new LearningKpis.Target("goal", channel, metric, 100L, start, end);
  }

  JsonNode targets(String metric) {
    return json.valueToTree(List.of(target(metric)));
  }

  JsonNode post(Object views) {
    var map = new HashMap<String, Object>();
    map.put("deliverableId", unit);
    map.put("submissionRevision", 1);
    map.put("periodStart", start.toString());
    map.put("periodEnd", end.toString());
    map.put("views", views);
    map.put("likes", 40);
    map.put("comments", 30);
    map.put("shares", 30);
    return json.valueToTree(map);
  }

  List<LearningKpis.Publication> pubs() {
    return List.of(new LearningKpis.Publication(unit, channel, 1, "TIKTOK:123"));
  }

  @Test
  void targetsMustBeAgreedBeforePeriodAndBelongToMembers() {
    LearningKpis.validate(List.of(target("VIEWS")), Set.of(channel), start.minusSeconds(1));
    assertThatThrownBy(
            () -> LearningKpis.validate(List.of(target("VIEWS")), Set.of(channel), start))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () -> LearningKpis.validate(List.of(target("VIEWS")), Set.of(UUID.randomUUID()), null))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> LearningKpis.validate(List.of(target("GMV")), Set.of(channel), null))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                LearningKpis.validate(
                    List.of(target("VIEWS"), target("VIEWS")), Set.of(channel), null))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void knownZeroMissingAndOverflowAreNotConfused() {
    assertThat(
            LearningKpis.attainment(
                targets("VIEWS"), json.valueToTree(List.of(post(0))), pubs(), end))
        .isZero();
    assertThat(
            LearningKpis.attainment(
                targets("VIEWS"), json.valueToTree(List.of(post(null))), pubs(), end))
        .isNull();
    assertThat(
            LearningKpis.attainment(
                targets("VIEWS"), json.valueToTree(List.of(post(200))), pubs(), end))
        .isEqualTo(1);
    assertThat(
            LearningKpis.attainment(
                targets("VIEWS"), json.valueToTree(List.of(post(-1))), pubs(), end))
        .isNull();
  }

  @Test
  void engagementRequiresAllInputsAndExactPeriodAndRevision() {
    var p = (com.fasterxml.jackson.databind.node.ObjectNode) post(100);
    assertThat(
            LearningKpis.attainment(
                targets("ENGAGEMENTS"), json.valueToTree(List.of(p)), pubs(), end))
        .isEqualTo(1);
    p.remove("shares");
    assertThat(
            LearningKpis.attainment(
                targets("ENGAGEMENTS"), json.valueToTree(List.of(p)), pubs(), end))
        .isNull();
    p = (com.fasterxml.jackson.databind.node.ObjectNode) post(100);
    p.put("periodEnd", end.plusSeconds(1).toString());
    assertThat(LearningKpis.attainment(targets("VIEWS"), json.valueToTree(List.of(p)), pubs(), end))
        .isNull();
    assertThat(
            LearningKpis.attainment(
                targets("VIEWS"),
                json.valueToTree(List.of(post(100))),
                pubs(),
                end.minusSeconds(1)))
        .isNull();
  }

  @Test
  void duplicatePublicationOnlyCountsOnceAndContradictionIsUnavailable() {
    UUID second = UUID.randomUUID();
    var p2 = (com.fasterxml.jackson.databind.node.ObjectNode) post(50);
    p2.put("deliverableId", second.toString());
    var publications = new ArrayList<>(pubs());
    publications.add(new LearningKpis.Publication(second, channel, 1, "TIKTOK:123"));
    assertThat(
            LearningKpis.attainment(
                targets("VIEWS"), json.valueToTree(List.of(post(50), p2)), publications, end))
        .isEqualTo(.5);
    p2.put("views", 60);
    assertThat(
            LearningKpis.attainment(
                targets("VIEWS"), json.valueToTree(List.of(post(50), p2)), publications, end))
        .isNull();
  }

  @Test
  void jsonDoesNotCoerceDecimalStringOrOutOfRangeTarget() {
    for (String bad : List.of("\"100\"", "1.5", "9223372036854775808")) {
      String body =
          "{\"key\":\"x\",\"creatorId\":\""
              + channel
              + "\",\"metric\":\"VIEWS\",\"target\":"
              + bad
              + ",\"periodStart\":\""
              + start
              + "\",\"periodEnd\":\""
              + end
              + "\"}";
      assertThatThrownBy(() -> json.readValue(body, LearningKpis.Target.class))
          .isInstanceOf(Exception.class);
    }
  }
}
