package com.influencermatch.backend.campaign;

import com.influencermatch.backend.brand.BrandProfile;
import com.influencermatch.backend.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Entity
@Table(name = "campaign")
@AttributeOverride(name = "id", column = @Column(name = "campaignId", updatable = false, nullable = false))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class Campaign extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "brandProfileId", nullable = false) private BrandProfile brandProfile;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "productService", nullable = false, length = 500) private String productService;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private CampaignObjective objective;
    @Builder.Default @JdbcTypeCode(SqlTypes.ARRAY) @Column(name = "targetAudiences", nullable = false) private List<String> targetAudiences = new ArrayList<>();
    @Builder.Default @JdbcTypeCode(SqlTypes.ARRAY) @Column(nullable = false) private List<String> platforms = new ArrayList<>();
    @Builder.Default @JdbcTypeCode(SqlTypes.ARRAY) @Column(nullable = false) private List<String> niches = new ArrayList<>();
    @Builder.Default @JdbcTypeCode(SqlTypes.ARRAY) @Column(nullable = false) private List<String> locations = new ArrayList<>();
    @Column(name = "followerMin") private Long followerMin;
    @Column(name = "followerMax") private Long followerMax;
    @Column(name = "contentType", length = 100) private String contentType;
    @Column(name = "budgetMin", precision = 19, scale = 2) private BigDecimal budgetMin;
    @Column(name = "budgetMax", precision = 19, scale = 2) private BigDecimal budgetMax;
    @Column(name = "startDate") private LocalDate startDate;
    @Column(name = "endDate") private LocalDate endDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private CampaignStatus status;
}
