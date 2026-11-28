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

    /** Match supported product concepts, not every word of a long marketing brief. */
    public static String productEvidenceQuery(String product, String campaignName) {
        var niches = productNiches(product, campaignName);
        if (niches.isEmpty()) return "";
        var terms = new LinkedHashSet<String>();
        for (var topic : PRODUCT_TOPICS)
            if (topic.niches().stream().anyMatch(niche -> niches.contains(niche.toLowerCase(Locale.ROOT))))
                terms.addAll(topic.terms());
        return terms.stream().map(term -> "\"" + term + "\"")
            .collect(java.util.stream.Collectors.joining(" OR "));
    }

    private static String normalize(String text) {
        return java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").replace('đ', 'd').replace('Đ', 'D')
            .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
    public static List<String> expand(List<String> niches) {
        return CreatorDiscoveryContract.expand(niches);
    }

    /** Related categories are used only for explicitly labelled expanded results. */
    public static List<String> related(List<String> niches, List<String> productNiches) {
        var result = new LinkedHashSet<>(expand(niches));
        // Inferred product topics cannot replace an explicitly selected category.
        if (result.isEmpty()) result.addAll(productNiches);
        if (result.stream().anyMatch(value -> value.contains("skincare") || value.contains("chăm sóc da")
                || value.contains("makeup") || value.contains("trang điểm") || value.contains("haircare")
                || value.contains("chăm sóc tóc") || value.contains("beauty") || value.contains("mỹ phẩm"))) {
            // All currently supported product topics belong to the beauty family.
            result.addAll(productNiches);
            result.addAll(expand(List.of("BEAUTY")));
        }
        return List.copyOf(result);
    }
}
