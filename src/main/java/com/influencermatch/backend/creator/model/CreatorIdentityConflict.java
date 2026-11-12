package com.influencermatch.backend.creator.model;

import com.influencermatch.backend.common.BaseEntity;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "creator_identity_conflict")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CreatorIdentityConflict extends BaseEntity {

  @Column(name = "conflict_type", nullable = false, length = 64)
  private String conflictType; // ACCOUNT_ALREADY_LINKED, NAMESPACE_MISMATCH, DUPLICATE_CLAIM

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_account_id")
  private Creator sourceAccount;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "target_master_id")
  private CreatorMaster targetMaster;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "conflict_details", nullable = false)
  private Map<String, Object> conflictDetails;

  @Builder.Default
  @Column(nullable = false, length = 32)
  private String status = "PENDING_MANAGER"; // PENDING_MANAGER, RESOLVED, REJECTED

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "resolved_by")
  private User resolvedBy;

  @Column(name = "resolved_at")
  private LocalDateTime resolvedAt;

  @Column(name = "resolution_notes", columnDefinition = "TEXT")
  private String resolutionNotes;
}
