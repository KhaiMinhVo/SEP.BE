package com.influencermatch.backend.audit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.audit.model.AuditLog;
import com.influencermatch.backend.audit.repository.AuditLogRepository;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.support.TestActors;
import com.influencermatch.backend.user.enums.Role;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.*;

class AuditServiceTest {
  final AuditLogRepository logs=mock(AuditLogRepository.class);
  final AuditService service=new AuditService(logs,new ObjectMapper());
  @AfterEach void clear(){SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
  @Test void recordsActorEntityPayloadAndRequestAddress(){
    var actor=TestActors.user(Role.ADMIN);TestActors.authenticate(actor);var id=UUID.randomUUID();
    var req=new MockHttpServletRequest();req.setRemoteAddr("192.0.2.10");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    service.record("CHANGE","User",id,Map.of("status","ACTIVE"),Map.of("status","LOCKED"),"Reason");
    var saved=ArgumentCaptor.forClass(AuditLog.class);verify(logs).save(saved.capture());var row=saved.getValue();
    assertThat(row.getUserId()).isEqualTo(actor.getId());assertThat(row.getEntityId()).isEqualTo(id);
    assertThat(row.getAction()).isEqualTo("CHANGE");assertThat(row.getEntityType()).isEqualTo("User");
    assertThat(row.getOldValue().path("status").asText()).isEqualTo("ACTIVE");assertThat(row.getNewValue().path("status").asText()).isEqualTo("LOCKED");
    assertThat(row.getDescription()).isEqualTo("Reason");assertThat(row.getIpAddress()).isEqualTo("192.0.2.10");assertThat(row.getCreatedAt()).isNotNull();
  }
  @Test void backgroundInvocationHasNoRequestIpAndAcceptsNullPayloads(){
    TestActors.authenticate(TestActors.user(Role.ADMIN));service.record("CREATE","User",UUID.randomUUID(),null,null,null);
    var saved=ArgumentCaptor.forClass(AuditLog.class);verify(logs).save(saved.capture());
    assertThat(saved.getValue().getIpAddress()).isNull();assertThat(saved.getValue().getOldValue().isNull()).isTrue();
  }
  @Test void unauthenticatedActorCannotCreateAudit(){
    assertThatThrownBy(()->service.record("CREATE","User",UUID.randomUUID(),null,null,null)).isInstanceOf(BusinessException.class);
    verifyNoInteractions(logs);
  }
}
