package com.influencermatch.backend.taxonomy.model;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "niche_catalog")
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
}
