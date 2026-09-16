package com.influencermatch.backend.creator;
import jakarta.persistence.*; import lombok.*; import java.util.UUID;
@Entity @Table(name="category",uniqueConstraints=@UniqueConstraint(name="uxCategoryName",columnNames="name"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder public class Category {
 @Id @GeneratedValue(strategy=GenerationType.UUID) @Column(name="categoryId") private UUID id;
 @Column(nullable=false,length=120) private String name;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="parentCategoryId") private Category parent;
}
