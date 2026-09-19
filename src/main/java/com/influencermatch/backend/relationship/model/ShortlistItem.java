package com.influencermatch.backend.relationship.model;
import com.influencermatch.backend.relationship.model.*;
import com.influencermatch.backend.relationship.repository.*;
import com.influencermatch.backend.relationship.enums.*;
import com.influencermatch.backend.campaign.model.Campaign;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.user.model.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "shortlist_item", uniqueConstraints = @UniqueConstraint(name = "ux_shortlist_campaign_creator", columnNames = {"campaign_id", "creator_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShortlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Creator creator;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShortlistPriority priority;

    private String note;

    @Column(nullable = false)
    private LocalDateTime addedAt;
}
