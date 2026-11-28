package com.influencermatch.backend.discovery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Packaged contract shared with the crawler; configuration labels are never classification evidence. */
public final class CreatorDiscoveryContract {
    public record Label(String code, List<String> aliases, List<String> searchTerms, String category) {}
    public record Definition(String version, String engagementRateUnit, long minFollowers,
        int maxRecentPosts, int embeddingDimensions, double minDataConfidence,
        List<String> acceptedCreatorTypes, List<Label> categories, List<Label> niches) {}
    public record Classification(String category, String niche) {}
    public static final Definition DEFINITION = read();
    private static final List<Label> LABELS = labels();
    private CreatorDiscoveryContract() {}

    private static Definition read() {
        try (var in = CreatorDiscoveryContract.class.getResourceAsStream("/contracts/creator-discovery-v1.json")) {
            if (in == null) throw new IllegalStateException("Creator discovery contract missing");
            return new ObjectMapper().readValue(in, Definition.class);
        } catch (java.io.IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
    private static List<Label> labels() {
        var all = new ArrayList<>(DEFINITION.categories());
        all.addAll(DEFINITION.niches());
        return List.copyOf(all);
    }
    private static String key(String text) {
        return java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFC)
            .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    public static Optional<Label> label(String value) {
        String normalized = key(value);
        return LABELS.stream().filter(l -> key(l.code()).equals(normalized)
            || l.aliases().stream().anyMatch(a -> key(a).equals(normalized))).findFirst();
    }
    public static String canonicalLabel(String value) {
        if (value == null || Set.of("", "unknown", "general", "null", "none", "n/a").contains(key(value)))
            return "UNKNOWN";
        return label(value).map(Label::code).orElse(value.trim());
    }
    public static String parent(String value) {
        return label(value).map(l -> l.category() == null ? l.code() : l.category()).orElse(null);
    }
    public static List<String> nicheParts(String value) {
        if (value == null || value.isBlank()) return List.of();
        // Some legacy whole labels contain commas; resolve them before splitting a multi-label field.
        if (label(value).isPresent()) return List.of(canonicalLabel(value));
        return Arrays.stream(value.split(",")).map(CreatorDiscoveryContract::canonicalLabel)
            .filter(v -> !v.equals("UNKNOWN")).distinct().toList();
    }
    public static Classification classify(String category, String niche) {
        var parts = nicheParts(niche);
        var parents = new LinkedHashSet<String>();
        for (var part : parts) {
            var parent = parent(part);
            if (parent != null) parents.add(parent);
        }
        String resultCategory = canonicalLabel(category);
        var broad = parent(resultCategory);
        if (broad != null) resultCategory = broad;
        if (parents.size() == 1) resultCategory = parents.iterator().next();
        else if (parents.size() > 1 && !parents.contains(resultCategory)) resultCategory = "UNKNOWN";
        return new Classification(resultCategory, parts.isEmpty() ? "UNKNOWN" : String.join(", ", parts));
    }
    public static List<String> expand(List<String> values) {
        var result = new LinkedHashSet<String>();
        if (values == null) return List.of();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            result.add(key(value));
            label(value).ifPresent(l -> {
                result.add(key(l.code()));
                l.aliases().forEach(a -> result.add(key(a)));
            });
        }
        return List.copyOf(result);
    }
}
