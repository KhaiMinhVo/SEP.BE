package com.influencermatch.backend.discovery;

import static org.assertj.core.api.Assertions.*;
import com.influencermatch.backend.discovery.service.CreatorDiscoveryContract;
import com.influencermatch.backend.discovery.service.DiscoveryNiches;
import java.util.*;
import org.junit.jupiter.api.Test;

class CreatorDiscoveryContractTest {
  @Test void observedProductGroupsHaveSeparateCategoryFamilies() {
    assertThat(CreatorDiscoveryContract.classify("BEAUTY", "Gia dụng").category()).isEqualTo("HOME");
    assertThat(CreatorDiscoveryContract.classify("BEAUTY", "Thực phẩm, Gia dụng").category()).isEqualTo("UNKNOWN");
    assertThat(CreatorDiscoveryContract.classify("FOOD", "Thực phẩm, Gia dụng").category()).isEqualTo("FOOD");
    assertThat(CreatorDiscoveryContract.classify("BEAUTY", "UNKNOWN").niche()).isEqualTo("UNKNOWN");
    assertThat(DiscoveryNiches.expand(List.of("FOOD"))).doesNotContain("home", "pets", "gia dụng", "thú cưng");
    assertThat(DiscoveryNiches.expand(List.of("LIFESTYLE"))).doesNotContain("home", "health", "gaming");
  }

  @Test void everyPublishedAliasResolvesToTheSameCodeInSearchAndIngestion() {
    var labels = new ArrayList<>(CreatorDiscoveryContract.DEFINITION.categories());
    labels.addAll(CreatorDiscoveryContract.DEFINITION.niches());
    for (var label : labels) {
      var aliases = new ArrayList<>(label.aliases()); aliases.add(label.code());
      for (var alias : aliases) {
        var result = CreatorDiscoveryContract.classify(alias, alias);
        assertThat(result.niche()).as(alias).isEqualTo(label.code());
        assertThat(result.category()).as(alias).isEqualTo(label.category() == null ? label.code() : label.category());
        assertThat(DiscoveryNiches.expand(List.of(alias))).contains(label.code().toLowerCase(Locale.ROOT));
      }
    }
  }

  @Test void customObservedNichesArePreservedAndCommaContainingAliasesRemainWhole() {
    assertThat(CreatorDiscoveryContract.classify("FOOD", "Ẩm thực miền Tây").niche()).isEqualTo("Ẩm thực miền Tây");
    assertThat(CreatorDiscoveryContract.classify("UNKNOWN", "Đời sống, Sức khỏe & Gia đình").niche()).isEqualTo("LIFESTYLE");
  }
}
