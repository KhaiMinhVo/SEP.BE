package com.influencermatch.backend.discovery.service;

import java.util.*;

/** Observed niche aliases, independent of crawl-field configuration. */
public final class DiscoveryNiches {
    private DiscoveryNiches() {}
    private static final List<List<String>> ALIASES = List.of(
        List.of("BEAUTY", "Beauty", "Làm đẹp", "Làm đẹp & Mỹ phẩm", "Mỹ phẩm & Làm đẹp"),
        List.of("SKINCARE", "Chăm sóc da", "Chăm sóc da & Skincare"),
        List.of("FASHION", "Fashion", "Thời trang", "Thời trang & Phong cách", "Thời trang & Phụ kiện")
    );
    public static List<String> expand(List<String> niches) {
        var result = new LinkedHashSet<String>();
        if (niches == null) return List.of();
        for (String niche : niches) {
            if (niche == null || niche.isBlank()) continue;
            String value = niche.trim().toLowerCase(Locale.ROOT);
            result.add(value);
            for (var group : ALIASES)
                if (group.stream().anyMatch(alias -> alias.equalsIgnoreCase(value)))
                    group.forEach(alias -> result.add(alias.toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(result);
    }
}
