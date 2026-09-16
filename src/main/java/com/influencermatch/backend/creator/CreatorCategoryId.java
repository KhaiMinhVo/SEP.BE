package com.influencermatch.backend.creator;
import jakarta.persistence.*; import lombok.*; import java.io.Serializable; import java.util.UUID;
@Embeddable @Getter @Setter @EqualsAndHashCode @NoArgsConstructor @AllArgsConstructor public class CreatorCategoryId implements Serializable { private UUID creatorId; private UUID categoryId; }
