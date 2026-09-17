package com.influencermatch.backend.creator;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "creator", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"platform", "external_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Creator {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column
    private UUID id;

    @Column(nullable = false, length = 50)
    private String platform;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(name = "user_name", nullable = false, length = 255)
    private String userName;

    @Column(length = 150)
    private String location;

    @Column(name = "profile_url", length = 1000)
    private String profileUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
