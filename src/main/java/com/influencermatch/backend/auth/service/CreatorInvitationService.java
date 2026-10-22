package com.influencermatch.backend.auth.service;

import com.influencermatch.backend.auth.dto.CreatorRegistrationDtos.*;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.jwt.*;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class CreatorInvitationService {
  private final UserRepository users;
  private final com.influencermatch.backend.creator.repository.CreatorMasterRepository masters;
  private final CreatorInvitationRepository invitations;
  private final CreatorInvitationDeliveryRepository deliveries;
  private final VerificationEmailSender sender;
  private final EmailVerificationService verification;
  private final PasswordEncoder encoder;
  private final AuditService audit;
  private final Clock clock;
  private final String frontend;
  private final SecureRandom random=new SecureRandom();
  public CreatorInvitationService(UserRepository users,com.influencermatch.backend.creator.repository.CreatorMasterRepository masters,CreatorInvitationRepository invitations,
      CreatorInvitationDeliveryRepository deliveries,VerificationEmailSender sender,EmailVerificationService verification,
      PasswordEncoder encoder,AuditService audit,Clock clock,
      @Value("${application.auth.creator-invitation.frontend-base-url:http://localhost:5173}") String frontend) {
    this.users=users;this.masters=masters;this.invitations=invitations;this.deliveries=deliveries;this.sender=sender;
    this.verification=verification;this.encoder=encoder;this.audit=audit;this.clock=clock;
    var uri=java.net.URI.create(frontend);
    if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getUserInfo()!=null)
      throw new IllegalArgumentException("Creator invitation frontend must be an absolute HTTP(S) base URL");
    this.frontend=frontend.replaceAll("/+$","");
  }
  @Transactional @PreAuthorize("hasAuthority('INVITE_CREATOR')")
  public InvitationResponse invite(InvitationRequest request) {
    Permissions.require(Permission.INVITE_CREATOR);users.lockRoleChanges();sender.requireConfigured();
    String email=request.email();
    if(users.existsByEmail(email))throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS,"This email already has an account");
    Instant now=clock.instant();var invitation=invitations.findByEmail(email).orElseGet(CreatorInvitation::new);
    if((invitation.getLastSentAt()!=null && now.isBefore(invitation.getLastSentAt().plusSeconds(60)))
        || deliveries.countByEmailAndSentAtAfter(email,now.minusSeconds(3600))>=5)
      throw new BusinessException(ErrorCode.EMAIL_OTP_RATE_LIMITED,"Please wait before sending another invitation");
    byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    if(invitation.getId()==null)invitation.setId(UUID.randomUUID());
    invitation.setEmail(email);invitation.setTokenHash(hash(token));invitation.setConsumedAt(null);
    invitation.setExpiresAt(now.plusSeconds(48*3600));invitation.setLastSentAt(now);invitation.setInvitedBy(Permissions.actor().getId());
    invitations.save(invitation);deliveries.deleteByEmailAndSentAtBefore(email,now.minusSeconds(3600));
    deliveries.save(new CreatorInvitationDelivery(UUID.randomUUID(),email,now));
    audit.record("INVITE_CREATOR","CreatorInvitation",invitation.getId(),null,
        Map.of("email",email,"expiresAt",invitation.getExpiresAt().toString()),"Creator registration invitation");
    String link=UriComponentsBuilder.fromUriString(frontend+"/auth/creator/register").queryParam("token",token).build().encode().toUriString();
    sender.sendCreatorInvitation(email,link);return response(invitation);
  }
  @Transactional(readOnly=true)
  public InvitationResponse resolve(String token) {return response(valid(token));}

  @Transactional
  public void register(RegisterCreatorRequest request) {
    // All account creation and invitation consumption use the existing account-write guard.
    users.lockRoleChanges();var invitation=valid(request.invitationToken());
    if(users.existsByEmail(invitation.getEmail()))throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS,"This email already has an account");
    User user=users.saveAndFlush(User.builder().email(invitation.getEmail()).fullName(request.fullName().trim())
        .passwordHash(encoder.encode(request.password())).role(Role.CREATOR).status(UserStatus.ACTIVE).emailVerified(false).build());
    masters.save(com.influencermatch.backend.creator.model.CreatorMaster.builder()
        .user(user).canonicalName(user.getFullName()).status("ACTIVE").build());
    invitation.setConsumedAt(clock.instant());verification.sendInitial(user);
  }
  private CreatorInvitation valid(String token) {
    return invitations.findByTokenHash(hash(token)).filter(i->i.getConsumedAt()==null && i.getExpiresAt().isAfter(clock.instant())).orElseThrow(this::invalid);
  }
  private BusinessException invalid() {return new BusinessException(ErrorCode.CREATOR_INVITATION_INVALID,"Invitation is invalid, expired or already used");}
  private InvitationResponse response(CreatorInvitation i) {return new InvitationResponse(i.getId(),i.getEmail(),i.getExpiresAt());}
  private String hash(String token) {
    try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
    catch(NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
  }
}
