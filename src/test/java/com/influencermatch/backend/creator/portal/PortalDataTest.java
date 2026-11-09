package com.influencermatch.backend.creator.portal;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class PortalDataTest {
  final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

  ObjectNode patch(ObjectNode old, String text) throws Exception {
    return PortalData.patch(json, old, json.readTree(text));
  }

  @Test
  void emptyIsPrivateWithoutFabricatedData() {
    var e = PortalData.empty(json);
    assertThat(e.path("introduction").isNull()).isTrue();
    assertThat(e.at("/contacts/bookingEmail/visibility").asText()).isEqualTo("PRIVATE");
    assertThat(e.at("/sharing/portfolio").asBoolean()).isTrue();
    assertThat(e.at("/sharing/availability").asBoolean()).isFalse();
    assertThat(e.at("/availability/status").isNull()).isTrue();
  }

  @Test
  void omittedFieldsSurviveAndNullOrEmptyListsClear() throws Exception {
    var a =
        patch(
            PortalData.empty(json),
            "{\"introduction\":\"  Creator  \",\"serviceAreas\":[\" Hanoi"
                + " \",\"Hanoi\",\"Danang\"]}");
    assertThat(a.path("introduction").asText()).isEqualTo("Creator");
    assertThat(a.path("serviceAreas").size()).isEqualTo(2);
    assertThat(patch(a, "{}")).isEqualTo(a);
    assertThat(patch(a, "{\"acceptedNiches\":[\"Beauty\"]}").path("introduction").asText())
        .isEqualTo("Creator");
    var cleared = patch(a, "{\"introduction\":null,\"serviceAreas\":[]}");
    assertThat(cleared.path("introduction").isNull()).isTrue();
    assertThat(cleared.path("serviceAreas").isEmpty()).isTrue();
  }

  @Test
  void reorderedListsAreNoop() throws Exception {
    var a = patch(PortalData.empty(json), "{\"serviceAreas\":[\"B\",\"A\"]}");
    assertThat(patch(a, "{\"serviceAreas\":[\"A\",\"B\"]}")).isEqualTo(a);
  }

  @Test
  void contactVisibilityIsStrictAndNestedOmissionPreservesValue() throws Exception {
    var a =
        patch(
            PortalData.empty(json),
            "{\"contacts\":{\"bookingEmail\":{\"value\":\"creator@example.test\",\"visibility\":\"BRANDS\"}}}");
    var b = patch(a, "{\"contacts\":{\"bookingEmail\":{\"visibility\":\"PRIVATE\"}}}");
    assertThat(b.at("/contacts/bookingEmail/value").asText()).isEqualTo("creator@example.test");
    var c = patch(b, "{\"contacts\":{\"bookingEmail\":null}}");
    assertThat(c.at("/contacts/bookingEmail/value").isNull()).isTrue();
    assertThat(c.at("/contacts/bookingEmail/visibility").asText()).isEqualTo("PRIVATE");
  }

  @Test
  void invalidAndUnsupportedInputFailsWithoutEchoingPrivateValues() throws Exception {
    for (String s :
        List.of(
            "{\"bankAccount\":\"secret\"}",
            "{\"contacts\":{\"bookingEmail\":{\"value\":\"PRIVATE_SECRET\"}}}",
            "{\"sharing\":{\"portfolio\":null}}",
            "{\"contacts\":{\"zalo\":{\"visibility\":\"PUBLIC\"}}}",
            "{\"availability\":{\"status\":\"BOOKED\"}}",
            "{\"availability\":{\"availableFrom\":\"tomorrow\"}}"))
      assertThatThrownBy(() -> patch(PortalData.empty(json), s))
          .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class)
          .hasMessageNotContaining("PRIVATE_SECRET");
  }

  @Test
  void portfolioRequiresSafeHttpsAndOwnedUniqueIds() throws Exception {
    for (String url :
        List.of("http://example.test/x", "javascript:alert(1)", "https://user:pass@example.test/x"))
      assertThatThrownBy(
              () ->
                  patch(
                      PortalData.empty(json),
                      json.createObjectNode()
                          .set(
                              "portfolio",
                              json.createArrayNode()
                                  .add(
                                      json.createObjectNode().put("title", "Work").put("url", url)))
                          .toString()))
          .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
    var a =
        patch(
            PortalData.empty(json),
            "{\"portfolio\":[{\"title\":\"Work\",\"url\":\"https://example.test/work\"}]}");
    assertThat(
            patch(
                a, "{\"portfolio\":[{\"title\":\"Work\",\"url\":\"https://example.test/work\"}]}"))
        .isEqualTo(a);
    var foreign = json.createObjectNode();
    foreign.set("portfolio", a.path("portfolio").deepCopy());
    ((ObjectNode) foreign.path("portfolio").get(0)).put("id", UUID.randomUUID().toString());
    assertThatThrownBy(() -> patch(a, foreign.toString()))
        .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
  }

  @Test
  void contactsVisibilityMatrixNeverTreatsPrivateAsShared() throws Exception {
    for (String visibility : List.of("PRIVATE", "BRANDS", "CONFIRMED_PARTNERS"))
      for (boolean partner : List.of(false, true)) {
        var contact =
            json.readTree("{\"value\":\"private-contact\",\"visibility\":\"" + visibility + "\"}");
        var r = CreatorPortalProjection.shared(contact, partner);
        assertThat(r.value() != null)
            .isEqualTo(
                visibility.equals("BRANDS") || visibility.equals("CONFIRMED_PARTNERS") && partner);
        assertThat(r.verified()).isFalse();
      }
  }

  @Test
  void availabilityIsSelfDeclaredAndCannotSetVerificationOrCapacity() throws Exception {
    var a =
        patch(
            PortalData.empty(json),
            "{\"availability\":{\"status\":\" OPEN \",\"note\":\" Available soon"
                + " \",\"availableFrom\":\"2026-11-10\"}}");
    assertThat(a.at("/availability/status").asText()).isEqualTo("OPEN");
    assertThat(a.at("/availability/note").asText()).isEqualTo("Available soon");
    assertThatThrownBy(() -> patch(a, "{\"availability\":{\"verified\":true}}"))
        .isInstanceOf(com.influencermatch.backend.exception.ValidationException.class);
  }
}
