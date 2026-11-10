package com.influencermatch.backend.creator.tiktok;

import static org.assertj.core.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TikTokUsernamesTest {
  @ParameterizedTest
  @CsvSource({
    "@mixigaming,mixigaming",
    "MIXIGAMING,mixigaming",
    "@@mixigaming,@mixigaming",
    "some.name_1,some.name_1"
  })
  void canonicalizesExactlyOneLeadingAt(String raw, String expected) {
    assertThat(TikTokUsernames.normalize(raw)).isEqualTo(expected);
  }

  @Test
  void trimsAndHandlesMissingUsername() {
    assertThat(TikTokUsernames.normalize(" \t@MiXiGaming\r\n")).isEqualTo("mixigaming");
    assertThat(TikTokUsernames.normalize(null)).isEmpty();
    assertThat(TikTokUsernames.normalize(" @ ")).isEmpty();
  }

  @Test
  void independentOfServerLocale() {
    var previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertThat(TikTokUsernames.normalize("MIXI")).isEqualTo("mixi");
    } finally {
      Locale.setDefault(previous);
    }
  }
}
