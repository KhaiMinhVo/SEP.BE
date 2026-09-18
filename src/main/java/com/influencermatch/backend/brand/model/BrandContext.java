package com.influencermatch.backend.brand.model;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.repository.*;
import com.influencermatch.backend.brand.service.*;
import com.influencermatch.backend.brand.enums.*;
import com.influencermatch.backend.brand.dto.*;
import com.influencermatch.backend.brand.controller.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "brand_context")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class BrandContext extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false, unique = true)
    private BrandProfile brandProfile;

    @Column(nullable = false)
    private int contextVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode profileContext;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode learnedPatterns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode evidenceRefs;

    public void updateProfileContext(JsonNode value) {
        profileContext = value;
        contextVersion++;
    }
}
