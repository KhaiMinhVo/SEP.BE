package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.enums.CreatorSourceType;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.model.PublicCreatorMetric;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

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

    public static Specification<PublicCreatorMetric> hasFollowerTier(String tier) {
        return (root, query, cb) -> {
            if (tier == null || tier.isBlank()) return cb.conjunction();
            String upper = tier.trim().toUpperCase();
            return switch (upper) {
                case "NANO" -> cb.lessThan(root.get("followers"), 10_000L);
                case "MICRO" -> cb.between(root.get("followers"), 10_000L, 99_999L);
                case "MACRO" -> cb.between(root.get("followers"), 100_000L, 999_999L);
                case "MEGA" -> cb.greaterThanOrEqualTo(root.get("followers"), 1_000_000L);
                default -> cb.conjunction();
            };
        };
    }

    public static Specification<PublicCreatorMetric> hasPlatformIn(List<String> platforms) {
        return (root, query, cb) -> {
            if (platforms == null || platforms.isEmpty()) return cb.conjunction();
            Join<PublicCreatorMetric, Creator> creatorJoin = root.join("creator");
            return creatorJoin.get("platform").in(platforms);
        };
    }

    public static Specification<PublicCreatorMetric> hasPlatform(String platform) {
        return (root, query, cb) -> {
            if (platform == null || platform.isBlank()) return cb.conjunction();
            Join<PublicCreatorMetric, Creator> creatorJoin = root.join("creator");
            return cb.equal(cb.upper(creatorJoin.get("platform")), platform.trim().toUpperCase());
        };
    }

    public static Specification<PublicCreatorMetric> hasNicheIn(List<String> niches) {
        return (root, query, cb) -> {
            if (niches == null || niches.isEmpty()) return cb.conjunction();
            List<Predicate> predicates = new ArrayList<>();
            for (String n : niches) {
                if (n != null && !n.isBlank()) {
                    String pattern = "%" + n.trim().toLowerCase() + "%";
                    predicates.add(cb.like(cb.lower(root.get("niche")), pattern));
                    predicates.add(cb.like(cb.lower(root.get("category")), pattern));
                }
            }
            if (predicates.isEmpty()) return cb.conjunction();
            return cb.or(predicates.toArray(new Predicate[0]));
        };
    }

    public static Specification<PublicCreatorMetric> hasNiche(String niche) {
        return (root, query, cb) -> {
            if (niche == null || niche.isBlank()) return cb.conjunction();
            String pattern = "%" + niche.trim().toLowerCase() + "%";
            return cb.or(
                cb.like(cb.lower(root.get("niche")), pattern),
                cb.like(cb.lower(root.get("category")), pattern)
            );
        };
    }

    public static Specification<PublicCreatorMetric> hasEngagementRateBetween(BigDecimal minEr, BigDecimal maxEr) {
        return (root, query, cb) -> {
            if (minEr != null && maxEr != null) {
                return cb.between(root.get("engagementRate"), minEr, maxEr);
            } else if (minEr != null) {
                return cb.greaterThanOrEqualTo(root.get("engagementRate"), minEr);
            } else if (maxEr != null) {
                return cb.lessThanOrEqualTo(root.get("engagementRate"), maxEr);
            }
            return cb.conjunction();
        };
    }

    public static Specification<PublicCreatorMetric> isClaimed(Boolean isClaimed) {
        return (root, query, cb) -> {
            if (isClaimed == null) return cb.conjunction();
            Join<PublicCreatorMetric, Creator> creatorJoin = root.join("creator");
            Predicate isAuthorized = cb.or(
                cb.equal(creatorJoin.get("sourceType"), CreatorSourceType.AUTHORIZED),
                cb.isNotNull(creatorJoin.get("authorizedAt"))
            );
            return isClaimed ? isAuthorized : cb.not(isAuthorized);
        };
    }

    public static Specification<PublicCreatorMetric> hasFeeBetween(BigDecimal minFee, BigDecimal maxFee) {
        return (root, query, cb) -> {
            if (minFee == null && maxFee == null) return cb.conjunction();

            // Comparable Fee: actualFee != null ? actualFee : (estimatedFeeMin + estimatedFeeMax) / 2
            Expression<Number> actualFeeExp = root.get("actualFee");
            Expression<BigDecimal> estMinExp = cb.coalesce(root.get("estimatedFeeMin"), BigDecimal.ZERO);
            Expression<BigDecimal> estMaxExp = cb.coalesce(root.get("estimatedFeeMax"), BigDecimal.ZERO);
            Expression<Number> estAvgExp = cb.quot(cb.sum(estMinExp, estMaxExp), 2);
            Expression<Number> effectiveFee = cb.coalesce(actualFeeExp, estAvgExp);

            if (minFee != null && maxFee != null) {
                return cb.and(
                    cb.ge(effectiveFee, minFee),
                    cb.le(effectiveFee, maxFee)
                );
            } else if (minFee != null) {
                return cb.ge(effectiveFee, minFee);
            } else {
                return cb.le(effectiveFee, maxFee);
            }
        };
    }

    public static Specification<PublicCreatorMetric> hasLocationIn(List<String> locations) {
        return (root, query, cb) -> {
            if (locations == null || locations.isEmpty()) return cb.conjunction();
            return root.join("creator").get("location").in(locations);
        };
    }

    public static Specification<PublicCreatorMetric> notInCreatorIds(Set<UUID> ids) {
        return (root, query, cb) -> {
            if (ids == null || ids.isEmpty()) return cb.conjunction();
            Join<PublicCreatorMetric, Creator> creatorJoin = root.join("creator");
            return creatorJoin.get("id").in(ids).not();
        };
    }
}
