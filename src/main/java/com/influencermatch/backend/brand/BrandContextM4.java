package com.influencermatch.backend.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "brandContextM4")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class BrandContextM4 extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brandProfileId", nullable = false, unique = true) private BrandProfile brandProfile;
    @Column(name = "contextVersion", nullable = false) private int contextVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "profileContext", nullable = false) private JsonNode profileContext;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "learnedPatterns", nullable = false) private JsonNode learnedPatterns;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidenceRefs", nullable = false) private JsonNode evidenceRefs;

    public void updateProfileContext(JsonNode value) {
        profileContext = value;
        contextVersion++;
    }
}
