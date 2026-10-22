package com.influencermatch.backend.creator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.ExternalIdentityRepository;
import com.influencermatch.backend.creator.dto.CreatorClaimDtos.*;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.CreatorClaimService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class CreatorClaimServiceTest {
  @Mock CreatorRepository creators;
  @Mock CreatorMasterRepository masters;
  @Mock CreatorClaimRepository claims;
  @Mock ExternalIdentityRepository identities;
  @Mock UserRepository users;
  @Mock AuditService audit;
  @Mock EntityManager em;
  CreatorClaimService service;
  ValidatorFactory factory;
  User owner;
  Creator creator;

  @BeforeEach void setup() {
    factory = Validation.buildDefaultValidatorFactory();
    service = new CreatorClaimService(creators, masters, claims, identities, users, audit, em, factory.getValidator(), Optional.empty());
    owner = User.builder().id(UUID.randomUUID()).role(Role.CREATOR).status(UserStatus.ACTIVE).build();
    creator = Creator.builder().id(UUID.randomUUID()).build();
    lenient().when(masters.save(any())).thenAnswer(i->i.getArgument(0));
    login(owner);
  }
  @AfterEach void cleanup() { SecurityContextHolder.clearContext(); factory.close(); }
  void login(User user) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
  }
  void verified() {
    when(users.findById(owner.getId())).thenReturn(Optional.of(owner));
    owner.setEmailVerified(true);
  }
  void locked() { when(creators.findLockedById(creator.getId())).thenReturn(Optional.of(creator)); }
  ClaimRequest request() { return new ClaimRequest(List.of("https://example.test/proof"), "My channel"); }

  @Test void submissionStaysPublicAndAuditsWithoutEvidence() {
    verified(); locked();
    when(claims.saveAndFlush(any())).thenAnswer(i -> {
      CreatorClaim c = i.getArgument(0); c.setId(UUID.randomUUID()); return c;
    });
    var response = service.claim(creator.getId(), request());
    assertThat(response.status()).isEqualTo(CreatorClaimStatus.PENDING);
    assertThat(creator.getOwner()).isNull();
    assertThat(creator.getSourceType()).isEqualTo(CreatorSourceType.PUBLIC);
    verify(audit).record(eq("SUBMIT_CREATOR_CLAIM"), eq("CreatorClaim"), eq(response.id()), isNull(),
        eq(Map.of("status", "PENDING", "creatorId", creator.getId(), "claimantId", owner.getId())), anyString());
  }

  @Test void unverifiedEmailCannotClaim() {
    owner.setEmailVerified(false);
    when(users.findById(owner.getId())).thenReturn(Optional.of(owner));
    assertThatThrownBy(() -> service.claim(creator.getId(), request())).isInstanceOf(ForbiddenException.class);
    verify(claims, never()).saveAndFlush(any());
  }

  @Test void inactiveCreatorCannotClaim() {
    User current = User.builder().role(Role.CREATOR).status(UserStatus.LOCKED).build();
    when(users.findById(owner.getId())).thenReturn(Optional.of(current));
    assertThatThrownBy(() -> service.claim(creator.getId(), request())).isInstanceOf(ConflictException.class);
  }

  @Test void approvalRejectsCompetitorAndRevocationClearsOwnership() {
    User reviewer = User.builder().id(UUID.randomUUID()).role(Role.DATA_MANAGER).status(UserStatus.ACTIVE).build();
    login(reviewer); verified(); locked();
    CreatorClaim claim = CreatorClaim.builder().id(UUID.randomUUID()).creator(creator).claimant(owner).status(CreatorClaimStatus.PENDING).build();
    CreatorClaim competing = CreatorClaim.builder().id(UUID.randomUUID()).creator(creator).claimant(owner).status(CreatorClaimStatus.PENDING).build();
    when(claims.findById(claim.getId())).thenReturn(Optional.of(claim));
    when(claims.findByCreatorIdAndStatus(creator.getId(), CreatorClaimStatus.PENDING)).thenReturn(List.of(claim, competing));
    service.review(claim.getId(), new ReviewRequest(Decision.APPROVED, "Checked ownership"));
    assertThat(creator.getOwner()).isSameAs(owner);
    assertThat(creator.getSourceType()).isEqualTo(CreatorSourceType.AUTHORIZED);
    assertThat(creator.getAuthorizedAt()).isNotNull();
    assertThat(competing.getStatus()).isEqualTo(CreatorClaimStatus.REJECTED);
    service.revoke(claim.getId(), new RevokeRequest("Ownership withdrawn"));
    assertThat(creator.getOwner()).isNull();
    assertThat(creator.getAuthorizedAt()).isNull();
    assertThat(creator.getSourceType()).isEqualTo(CreatorSourceType.PUBLIC);
    assertThat(claim.getStatus()).isEqualTo(CreatorClaimStatus.REVOKED);
  }

  @Test void rejectedClaimNeverAuthorizesChannel() {
    login(User.builder().id(UUID.randomUUID()).role(Role.ADMIN).status(UserStatus.ACTIVE).build());
    locked();
    CreatorClaim claim = CreatorClaim.builder().id(UUID.randomUUID()).creator(creator).claimant(owner).status(CreatorClaimStatus.PENDING).build();
    when(claims.findById(claim.getId())).thenReturn(Optional.of(claim));
    assertThat(service.review(claim.getId(), new ReviewRequest(Decision.REJECTED, "Insufficient proof")).status())
        .isEqualTo(CreatorClaimStatus.REJECTED);
    assertThat(creator.getSourceType()).isEqualTo(CreatorSourceType.PUBLIC);
    assertThat(creator.getOwner()).isNull();
  }


  @Test void devMockIdentityIsNotAcceptedWhenMockServiceIsDisabled() {
    when(users.findById(owner.getId())).thenReturn(Optional.of(owner));
    lenient().when(identities.findByUserIdAndProvider(owner.getId(),ExternalIdentityProvider.DEV_MOCK))
        .thenReturn(Optional.of(ExternalIdentity.builder().provider(ExternalIdentityProvider.DEV_MOCK).user(owner).build()));
    assertThatThrownBy(()->service.claim(creator.getId(),request())).isInstanceOf(ForbiddenException.class);
    verify(identities).findByUserIdAndProvider(owner.getId(),ExternalIdentityProvider.DEV_MOCK);
    verifyNoInteractions(claims,audit);
  }

  @Test void missingChannelNeverCreatesClaim() {
    verified();
    assertThatThrownBy(()->service.claim(UUID.randomUUID(),request())).isInstanceOf(NotFoundException.class);
    verifyNoInteractions(claims,audit);
  }
  @Test void missingReviewTargetNeverAudits() {
    login(User.builder().id(UUID.randomUUID()).role(Role.DATA_MANAGER).status(UserStatus.ACTIVE).build());
    assertThatThrownBy(()->service.detail(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    verifyNoInteractions(audit);verify(claims,never()).save(any());
  }

}
