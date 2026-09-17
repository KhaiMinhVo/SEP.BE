package com.influencermatch.backend.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity
@Table(name = "campaign_context", uniqueConstraints = @UniqueConstraint(name = "ux_campaign_context_version", columnNames = {"campaign_id", "context_version"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class CampaignContext extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Campaign campaign;

    @Column(nullable = false)
    private int contextVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode contextData;

    @Column(nullable = false)
    private boolean active;

    @Column
    private LocalDateTime archivedAt;
}
