package com.influencermatch.backend.creator.model;

import com.influencermatch.backend.common.BaseEntity;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import java.util.Map;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "creator_identity_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CreatorIdentityAudit extends BaseEntity {

  @Column(name = "event_type", nullable = false, length = 64)
  private String eventType; // CLAIM_MASTER, LINK_ACCOUNT, UNLINK_ACCOUNT, REVOKE_ACCESS

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "master_id")
  private CreatorMaster master;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "account_id")
  private Creator account;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "performed_by", nullable = false)
  private User performedBy;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private Map<String, Object> details;
}
