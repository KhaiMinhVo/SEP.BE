package com.influencermatch.backend.auth.model;

import com.influencermatch.backend.common.BaseEntity;

import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.repository.UserRepository;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
@Entity @Table(name = "externalIdentity")
@AttributeOverride(name = "id", column = @Column(name = "id", nullable = false, updatable = false))
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
