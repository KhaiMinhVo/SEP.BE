package com.influencermatch.backend.recommendation.engine;

import static org.assertj.core.api.Assertions.*;

import com.influencermatch.backend.recommendation.dto.ScoreBreakdownDto;
import com.influencermatch.backend.recommendation.engine.presentation.DeterministicReasoningGenerator;
import java.util.Locale;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock("java.util.Locale.default")
class DeterministicReasoningLocaleTest {
  @ParameterizedTest
  @ValueSource(strings = {"vi-VN", "fr-FR", "en-US"})
  void numericEvidenceDoesNotDependOnHostLocale(String language) {
    Locale initial = Locale.getDefault();
    Locale format = Locale.getDefault(Locale.Category.FORMAT);
    Locale display = Locale.getDefault(Locale.Category.DISPLAY);
    try {
      Locale.setDefault(Locale.forLanguageTag(language));
      var score = ScoreBreakdownDto.builder().finalScore(81.25).m3Score(85.0).build();
      var card = new DeterministicReasoningGenerator().generate(null, null, score, null);
      assertThat(card.getSummary()).contains("81.3/100").doesNotContain("81,3");
      assertThat(card.getReasons()).anySatisfy(r -> assertThat(r).contains("85.0/100"));
    } finally {
      Locale.setDefault(initial);
      Locale.setDefault(Locale.Category.FORMAT, format);
      Locale.setDefault(Locale.Category.DISPLAY, display);
    }
  }
}
