package com.influencermatch.backend.support;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.creator.service.*;
import com.influencermatch.backend.audit.service.AuditService;
import org.springframework.test.util.ReflectionTestUtils;
public final class CreatorServiceFixtures {
  public static <T> T wire(T service) {
    set(service,"invitations",mock(com.influencermatch.backend.creator.repository.CreatorInvitationRepository.class));
    set(service,"clock",java.time.Clock.systemDefaultZone());
    set(service,"frontend","https://frontend.example.test");
    set(service,"sender",mock(com.influencermatch.backend.auth.service.VerificationEmailSender.class));
    set(service,"outbox",mock(com.influencermatch.backend.creator.email.ClaimEmailOutboxService.class));
    set(service,"em",mock(jakarta.persistence.EntityManager.class));
    set(service,"conflicts",new CreatorConflictAudit(mock(AuditService.class)));
    return service;
  }
  private static void set(Object target,String field,Object value) {
    if(org.springframework.util.ReflectionUtils.findField(target.getClass(),field)!=null)ReflectionTestUtils.setField(target,field,value);
  }
}
