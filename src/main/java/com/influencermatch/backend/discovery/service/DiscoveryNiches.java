package com.influencermatch.backend.discovery.service;

import java.util.*;

/** Observed niche aliases, independent of crawl-field configuration. */
public final class DiscoveryNiches {
    private DiscoveryNiches() {}
    private record ProductTopic(List<String> terms, List<String> niches) {}
    private static final List<ProductTopic> PRODUCT_TOPICS = List.of(
        new ProductTopic(List.of("kem chống nắng", "chống nắng", "sunscreen", "sunblock", "skincare", "dưỡng da", "chăm sóc da", "serum"),
            List.of("SKINCARE", "Chăm sóc da", "Chăm sóc da & Skincare", "Dưỡng da", "Review skincare")),
        new ProductTopic(List.of("dầu gội", "dầu xả", "haircare", "chăm sóc tóc", "nhuộm tóc"),
            List.of("HAIRCARE", "Chăm sóc tóc", "Chăm sóc và tạo mẫu tóc")),
        new ProductTopic(List.of("trang điểm", "makeup", "son môi", "phấn nền", "mascara"),
            List.of("MAKEUP", "Trang điểm", "Trang điểm & Mỹ phẩm"))
    );

    public static List<String> productNiches(String product, String campaignName) {
        for (String text : List.of(product == null ? "" : product, campaignName == null ? "" : campaignName)) {
            String normalized = normalize(text);
            var matches = new LinkedHashSet<String>();
            for (var topic : PRODUCT_TOPICS)
                if (topic.terms().stream().anyMatch(term -> java.util.regex.Pattern.compile(
                    "(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(normalize(term)) + "(?![\\p{L}\\p{N}])")
                    .matcher(normalized).find())) matches.addAll(topic.niches());
            if (!matches.isEmpty()) return expand(List.copyOf(matches));
        }
        return List.of();
    }

    public static boolean onlyBroadCategories(List<String> niches) {
        return niches != null && !niches.isEmpty() && niches.stream().allMatch(value ->
            List.of("beauty", "lam dep", "lam dep & my pham", "my pham & lam dep", "fashion", "lifestyle")
                .contains(normalize(value)));
    }

    private static String normalize(String text) {
        return java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").replace('đ', 'd').replace('Đ', 'D')
            .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
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
