package com.influencermatch.backend.creator.service;
import com.influencermatch.backend.audit.service.AuditService;
import com.influencermatch.backend.exception.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class CreatorConflictAudit {
  private final AuditService audit;
  public CreatorOwnershipConflictException reject(UUID creatorId,UUID invitationId,ErrorCode code,String reason) {
    var details=new LinkedHashMap<String,Object>();details.put("reason",code.name());
    if(invitationId!=null)details.put("invitationId",invitationId.toString());
    audit.record("REJECT_CREATOR_INVITATION_OR_OWNERSHIP","Creator",creatorId,null,details,reason);
    return new CreatorOwnershipConflictException(code,reason);
  }
}
