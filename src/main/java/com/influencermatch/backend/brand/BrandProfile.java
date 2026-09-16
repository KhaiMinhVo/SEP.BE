package com.influencermatch.backend.brand;

import com.influencermatch.backend.entity.BaseEntity;
import com.influencermatch.backend.entity.User;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.*;

@Entity
@Table(name = "brandProfile")
@AttributeOverride(name = "id", column = @Column(name = "brandProfileId", updatable = false, nullable = false))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class BrandProfile extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "userId", nullable = false, unique = true) private User user;
    @Column(name = "businessName", nullable = false, length = 200) private String businessName;
    @Column(length = 120) private String industry;
    @Builder.Default @JdbcTypeCode(SqlTypes.ARRAY) @Column(name = "productCategories", nullable = false) private List<String> productCategories = new ArrayList<>();
    @Column(length = 500) private String website;
    @Column(length = 150) private String location;
    @Builder.Default @JdbcTypeCode(SqlTypes.ARRAY) @Column(name = "targetMarkets", nullable = false) private List<String> targetMarkets = new ArrayList<>();
    @Builder.Default @JdbcTypeCode(SqlTypes.ARRAY) @Column(name = "targetAudiences", nullable = false) private List<String> targetAudiences = new ArrayList<>();
    @Column(name = "brandTone", length = 150) private String brandTone;
    @Builder.Default @JdbcTypeCode(SqlTypes.ARRAY) @Column(name = "preferredPlatforms", nullable = false) private List<String> preferredPlatforms = new ArrayList<>();
    @Column(length = 1000) private String description;
}
