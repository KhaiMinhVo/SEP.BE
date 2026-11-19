package com.influencermatch.backend.discovery.service;

import java.text.Normalizer;
import java.util.*;

/** Resolve campaign location labels before applying the creator-location filter. */
public final class DiscoveryLocations {
    private DiscoveryLocations() {}

    private static final Set<String> NATIONWIDE = Set.of(
        "toan quoc", "vietnam", "viet nam", "vn", "nationwide", "ca nuoc");
    private static final List<List<String>> CITY_ALIASES = List.of(
        List.of("Thành phố Hồ Chí Minh", "Hồ Chí Minh", "Ho Chi Minh City", "Ho Chi Minh",
            "TP.HCM", "TP HCM", "TP. HCM", "HCM", "TP Hồ Chí Minh", "Sài Gòn", "Saigon"),
        List.of("Hà Nội", "Ha Noi", "Hanoi", "Thành phố Hà Nội", "Hanoi City"),
        List.of("Đà Nẵng", "Da Nang", "Danang", "Thành phố Đà Nẵng", "Da Nang City"));

    public static List<String> expand(List<String> locations) {
        if (locations == null) return List.of();
        if (locations.stream().anyMatch(value -> NATIONWIDE.contains(normalize(value))))
            return List.of();
        var result = new LinkedHashSet<String>();
        for (String value : locations) {
            if (value == null || value.isBlank()) continue;
            result.add(value.trim().toLowerCase(Locale.ROOT));
            for (var aliases : CITY_ALIASES)
                if (aliases.stream().anyMatch(alias -> normalize(alias).equals(normalize(value))))
                    aliases.forEach(alias -> result.add(alias.toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").replace('đ', 'd').replace('Đ', 'D')
            .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
