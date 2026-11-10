package com.influencermatch.backend.cooperation;

import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.dto.*;
import com.influencermatch.backend.relationship.service.*;
import com.influencermatch.backend.security.permission.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class RelationshipBatchService {
  final RelationshipService relationships;
  final CampaignOwnershipService ownership;
  final PlatformTransactionManager manager;

  public Object batch(UUID campaign, CooperationDtos.Batch body) {
    Permissions.require(Permission.MANAGE_OWN_RELATIONSHIP);
    var tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.executeWithoutResult(s -> ownership.requireOwned(campaign));
    var ids = body.creatorIds();
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 100
        || ids.contains(null)
        || new HashSet<>(ids).size() != ids.size())
      throw new ValidationException("Distinct non-null creatorIds required");
    var results = new ArrayList<Object>();
    for (UUID id : ids) {
      try {
        var result =
            tx.execute(
                s ->
                    relationships.createRelationship(
                        campaign,
                        CreateRelationshipRequest.builder()
                            .creatorId(id)
                            .notes(body.notes())
                            .build()));
        results.add(Map.of("creatorId", id, "action", "SAVED", "item", result));
      } catch (BusinessException failure) {
        results.add(
            Map.of("creatorId", id, "action", "INVALID", "errorCode", failure.code().name()));
      } catch (Exception failure) {
        results.add(
            Map.of("creatorId", id, "action", "FAILED", "errorCode", "RELATIONSHIP_WRITE_FAILED"));
      }
    }
    return Map.of("campaignId", campaign, "results", results);
  }
}
