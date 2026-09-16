package com.influencermatch.backend.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity
@Table(name = "campaignContextM3", uniqueConstraints = @UniqueConstraint(name = "uxCampaignContextVersion", columnNames = {"campaignId", "contextVersion"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class CampaignContextM3 extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "campaignId", nullable = false) private Campaign campaign;
    @Column(name = "contextVersion", nullable = false) private int contextVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "contextData", nullable = false) private JsonNode contextData;
    @Column(nullable = false) private boolean active;
    @Column(name = "archivedAt") private LocalDateTime archivedAt;
}
