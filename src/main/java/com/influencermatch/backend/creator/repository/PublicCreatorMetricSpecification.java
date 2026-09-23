package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import com.influencermatch.backend.creator.model.Creator;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Join;
import java.util.List;

public class PublicCreatorMetricSpecification {

    public static Specification<PublicCreatorMetric> isFresh() {
        return (root, query, cb) -> cb.equal(root.get("freshnessStatus").as(String.class), "FRESH");
    }

    public static Specification<PublicCreatorMetric> hasFollowersBetween(Long min, Long max) {
        return (root, query, cb) -> {
            if (min != null && max != null) {
                return cb.between(root.get("followers"), min, max);
            } else if (min != null) {
                return cb.greaterThanOrEqualTo(root.get("followers"), min);
            } else if (max != null) {
                return cb.lessThanOrEqualTo(root.get("followers"), max);
            }
            return cb.conjunction();
        };
    }

    public static Specification<PublicCreatorMetric> hasPlatformIn(List<String> platforms) {
        return (root, query, cb) -> {
            if (platforms == null || platforms.isEmpty()) return cb.conjunction();
            Join<PublicCreatorMetric, Creator> creatorJoin = root.join("creator");
            return creatorJoin.get("platform").in(platforms);
        };
    }

    public static Specification<PublicCreatorMetric> hasNicheIn(List<String> niches) {
        return (root, query, cb) -> {
            if (niches == null || niches.isEmpty()) return cb.conjunction();
            return root.get("niche").in(niches);
        };
    }

    public static Specification<PublicCreatorMetric> hasLocationIn(List<String> locations) {
        return (root, query, cb) -> {
            if (locations == null || locations.isEmpty()) return cb.conjunction();
            return root.get("location").in(locations);
        };
    }

    public static Specification<PublicCreatorMetric> notInCreatorIds(java.util.Set<java.util.UUID> ids) {
        return (root, query, cb) -> {
            if (ids == null || ids.isEmpty()) return cb.conjunction();
            Join<PublicCreatorMetric, Creator> creatorJoin = root.join("creator");
            return creatorJoin.get("id").in(ids).not();
        };
    }
}
