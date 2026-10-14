package com.influencermatch.backend.discovery.repository;

import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.discovery.dto.DiscoveryContext;
import com.influencermatch.backend.discovery.dto.DiscoverySearchRequest;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public class CreatorDiscoverySpecification {

    public static Specification<PublicCreatorMetric> buildHybridSpec(DiscoveryContext ctx, DiscoverySearchRequest req) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. BUOC 1: EP DIEU KIEN CUNG TU CAMPAIGN (M3)
            if (ctx.getPlatforms() != null && !ctx.getPlatforms().isEmpty()) {
                predicates.add(root.get("creator").get("platform").in(ctx.getPlatforms()));
            }
            if (ctx.getNiches() != null && !ctx.getNiches().isEmpty()) {
                predicates.add(root.get("niche").in(ctx.getNiches()));
            }
            if (ctx.getFollowerMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("followers"), ctx.getFollowerMin()));
            }
            if (ctx.getFollowerMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("followers"), ctx.getFollowerMax()));
            }

            // 2. BUOC 2: BO LOC DONG TU GIAO DIEN BRAND TRUYEN XUONG
            if (req.getNiche() != null && !req.getNiche().trim().isEmpty()) {
                predicates.add(cb.equal(root.get("niche"), req.getNiche()));
            }
            if (req.getMinFollowers() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("followers"), req.getMinFollowers()));
            }
            if (req.getMaxFollowers() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("followers"), req.getMaxFollowers()));
            }

            // 3. BUOC 3: TIM KIEM MO (FUZZY SEARCH) BANG TRIGRAM
            if (req.getKeyword() != null && !req.getKeyword().trim().isEmpty()) {
                // Su dung custom function 'fts_match' de map voi toan tu @@ cua PostgreSQL
                predicates.add(cb.isTrue(cb.function(
                        "fts_match", 
                        Boolean.class, 
                        root.get("searchVector"), 
                        cb.literal(formatKeyword(req.getKeyword()))
                )));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String formatKeyword(String keyword) {
        // Chuyen doi khoang trang thanh toan tu OR (|) cho Postgres to_tsquery
        return keyword.trim().replaceAll("\\s+", " | ");
    }
}