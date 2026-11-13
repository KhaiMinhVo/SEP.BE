package com.influencermatch.backend.taxonomy.model;

import jakarta.persistence.*;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;

@Entity
@Table(name = "crawl_field")
@Getter @Setter @NoArgsConstructor
public class Niche {
  @Id @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;
  @Column(nullable = false, length = 100)
  private String name;
  @Column(nullable = false, length = 500)
  private String description = "";
  @Column(nullable = false)
  private boolean active = true;
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private List<String> keywords = new ArrayList<>();
}
