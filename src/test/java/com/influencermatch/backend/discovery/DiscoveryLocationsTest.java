package com.influencermatch.backend.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import com.influencermatch.backend.discovery.service.DiscoveryLocations;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryLocationsTest {
    @Test void nationwideLabelsDoNotRequireCreatorsToHaveCountryAsTheirCity() {
        for (String value : List.of("Toàn quốc", "Vietnam", "Việt Nam", "VN", "Cả nước", " Nationwide "))
            assertThat(DiscoveryLocations.expand(List.of(value))).as(value).isEmpty();
        assertThat(DiscoveryLocations.expand(List.of("Hà Nội", "Toàn quốc"))).isEmpty();
    }

    @Test void cityLabelsExpandWithoutIncludingUnrelatedCities() {
        var locations = DiscoveryLocations.expand(List.of("Thành phố Hồ Chí Minh"));
        assertThat(locations).contains("tp.hcm", "ho chi minh city", "hồ chí minh")
            .doesNotContain("hà nội", "vietnam");
        assertThat(DiscoveryLocations.expand(List.of("TP.HCM"))).containsAll(locations);
    }

    @Test void emptyAndCustomLocationsRemainSupported() {
        assertThat(DiscoveryLocations.expand(null)).isEmpty();
        assertThat(DiscoveryLocations.expand(List.of(" "))).isEmpty();
        assertThat(DiscoveryLocations.expand(List.of("  Cần Thơ  "))).containsExactly("cần thơ");
    }
}
