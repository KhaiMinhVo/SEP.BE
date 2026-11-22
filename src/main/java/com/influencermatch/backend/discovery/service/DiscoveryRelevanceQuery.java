package com.influencermatch.backend.discovery.service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Content words only: campaign boilerplate must not make unrelated creators rank equally. */
public final class DiscoveryRelevanceQuery {
    private DiscoveryRelevanceQuery() {}
    private static final Set<String> STOP = Set.of(
        "tim", "kiem", "can", "nguoi", "creator", "koc", "kol", "review", "chien", "dich",
        "quang", "cao", "san", "pham", "thuong", "hieu", "noi", "dung", "chia", "se",
        "cho", "cua", "voi", "nhung", "cac", "mot", "va", "la", "ve", "theo", "nhu",
        "muon", "hop", "tac", "yeu", "cau", "uu", "tien", "the", "and", "for", "with",
        "campaign", "brand", "product", "influencer", "content", "looking", "find");
    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}\\p{N}]*");

    public static String build(String... fields) {
        var words = new LinkedHashSet<String>();
        for (String field : fields) {
            if (field == null) continue;
            var matcher = WORD.matcher(field.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                String word = matcher.group();
                String folded = Normalizer.normalize(word, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").replace('đ', 'd');
                if (word.length() >= 2 && !STOP.contains(folded)) words.add(word);
            }
        }
        return String.join(" ", words);
    }
}
