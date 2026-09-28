package com.influencermatch.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
@Entity @Table(name = "externalIdentity")
@AttributeOverride(name = "id", column = @Column(name = "externalIdentityId", nullable = false, updatable = false))
public class ExternalIdentity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "userId", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30)
    private ExternalIdentityProvider provider;

    @Column(name = "providerSubject", nullable = false, length = 255)
    private String providerSubject;

    @Column(name = "providerEmail", nullable = false, length = 255)
    private String providerEmail;

    @Column(name = "emailVerified", nullable = false)
    private boolean emailVerified;
}
