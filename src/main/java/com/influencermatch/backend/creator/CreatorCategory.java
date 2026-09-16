package com.influencermatch.backend.creator;
import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal;
@Entity @Table(name="creatorCategory") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class CreatorCategory {
 @EmbeddedId private CreatorCategoryId id;
 @MapsId("creatorId") @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="creatorId") private Creator creator;
 @MapsId("categoryId") @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="categoryId") private Category category;
 @Column(precision=5,scale=4) private BigDecimal confidence; @Column(length=100) private String source;
}
