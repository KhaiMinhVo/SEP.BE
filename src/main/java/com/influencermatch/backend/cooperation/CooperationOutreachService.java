package com.influencermatch.backend.cooperation;

import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.relationship.dto.*;
import com.influencermatch.backend.relationship.service.*;
import com.influencermatch.backend.security.permission.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class CooperationOutreachService {
  final DealStore store;
  final DealService deals;
  final RelationshipService relationships;
  final CampaignOwnershipService ownership;
  final PlatformTransactionManager manager;

  public ShortlistOutreachResponse outreach(UUID campaign, ShortlistOutreachRequest body) {
    Permissions.require(Permission.MANAGE_OWN_SHORTLIST);
    Permissions.require(Permission.MANAGE_OWN_RELATIONSHIP);
    var tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.executeWithoutResult(s -> ownership.requireOwned(campaign));
    var ids = body.getShortlistItemIds();
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 100
        || ids.contains(null)
        || new HashSet<>(ids).size() != ids.size())
      throw new ValidationException("Distinct shortlistItemIds (1-100) required");
    var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
    var results = new HashMap<UUID, ShortlistOutreachResponse.OutreachItemResult>();
    for (UUID item : ids) {
      var row =
          store.optional(
              "select s.id,s.creator_id,c.master_id,c.display_name,c.user_name from shortlist_item"
                  + " s join creator c on c.id=s.creator_id where s.id=? and s.campaign_id=? and"
                  + " s.user_id=? and s.status<>'REMOVED'",
              item,
              campaign,
              Permissions.actor().getId());
      if (row.isEmpty()) {
        results.put(
            item,
            ShortlistOutreachResponse.OutreachItemResult.builder()
                .shortlistItemId(item)
                .contactStatus("INVALID")
                .message("Shortlist item is unavailable")
                .build());
        continue;
      }
      var r = row.get();
      String key =
          r.get("master_id") == null
              ? "CHANNEL:" + r.get("creator_id")
              : "MASTER:" + r.get("master_id");
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
    }
    for (var group : groups.values()) {
      try {
        var done = tx.execute(s -> process(campaign, group, body));
        done.forEach(r -> results.put(r.getShortlistItemId(), r));
      } catch (BusinessException failure) {
        for (var r : group)
          results.put(
              (UUID) r.get("id"),
              item(
                  r, null, null, null, failure.code().name(), "Cooperation could not be prepared"));
      } catch (Exception failure) {
        log.warn("Cooperation outreach group failed, campaignId={}", campaign);
        for (var r : group)
          results.put(
              (UUID) r.get("id"),
              item(r, null, null, null, "FAILED", "Cooperation could not be prepared"));
      }
    }
    var ordered = ids.stream().map(results::get).toList();
    return ShortlistOutreachResponse.builder()
        .campaignId(campaign)
        .totalRequested(ids.size())
        .contactedCount((int) ordered.stream().filter(r -> r.getRelationshipId() != null).count())
        .relationshipIds(
            ordered.stream()
                .map(ShortlistOutreachResponse.OutreachItemResult::getRelationshipId)
                .filter(Objects::nonNull)
                .distinct()
                .toList())
        .dispatchedInvitationIds(
            ordered.stream()
                .map(ShortlistOutreachResponse.OutreachItemResult::getInvitationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList())
        .items(ordered)
        .message("Cooperation preparation results; QUEUED does not mean sent")
        .build();
  }

  List<ShortlistOutreachResponse.OutreachItemResult> process(
      UUID campaign, List<Map<String, Object>> group, ShortlistOutreachRequest body) {
    deals.owned(campaign);
    var rels = new ArrayList<UUID>();
    for (var r : group) {
      var rel =
          relationships.createRelationship(
              campaign,
              CreateRelationshipRequest.builder()
                  .creatorId((UUID) r.get("creator_id"))
                  .shortlistItemId((UUID) r.get("id"))
                  .contactMethod(body.getContactMethod())
                  .notes(body.getOutreachMessage())
                  .build());
      rels.add(rel.getId());
    }
    var current =
        store.optional(
            "select d.* from cooperation_deal d join cooperation_member m on m.deal_id=d.id where"
                + " m.relationship_id=? and m.active",
            rels.getFirst());
    UUID id;
    if (current.isPresent()) {
      id = (UUID) current.get().get("id");
      var existingMembers =
          store.members(id).stream()
              .map(m -> (UUID) m.get("relationship_id"))
              .collect(java.util.stream.Collectors.toSet());
      if (!existingMembers.containsAll(rels))
        throw new BusinessException(
            ErrorCode.DEAL_STATE_CONFLICT,
            "Selected channels do not all belong to the existing Deal");
      var out = new ArrayList<ShortlistOutreachResponse.OutreachItemResult>();
      for (int n = 0; n < group.size(); n++)
        out.add(
            item(
                group.get(n),
                rels.get(n),
                id,
                null,
                "ALREADY_EXISTS",
                "Use the existing Deal; terms were not overwritten"));
      return out;
    }
    var created =
        deals.create(
            campaign, new CooperationDtos.Create(rels), "outreach-create:" + UUID.randomUUID());
    id = UUID.fromString(created.path("id").asText());
    UUID invitation = null;
    String status = "NEEDS_PROPOSAL";
    if (body.getProposedFee() != null
        && body.getOutreachMessage() != null
        && !body.getOutreachMessage().isBlank()
        && body.getDeliverables() != null
        && !body.getDeliverables().isEmpty()) {
      var channels =
          group.stream()
              .map(r -> (UUID) r.get("creator_id"))
              .collect(java.util.stream.Collectors.toSet());
      var deliverables =
          body.getDeliverables().stream().filter(d -> channels.contains(d.creatorId())).toList();
      var proposal =
          deals.propose(
              campaign,
              id,
              new CooperationDtos.Proposal(
                  1,
                  body.getProposedFee(),
                  body.getCurrency(),
                  deliverables,
                  body.getOutreachMessage()),
              "outreach-proposal:" + UUID.randomUUID());
      var sent =
          deals.send(
              campaign,
              id,
              new CooperationDtos.Send(proposal.path("revision").asLong()),
              "outreach-send:" + UUID.randomUUID());
      invitation = UUID.fromString(sent.path("invitationId").asText());
      status = sent.path("emailStatus").asText();
    }
    var result = new ArrayList<ShortlistOutreachResponse.OutreachItemResult>();
    for (int n = 0; n < group.size(); n++) {
      store.db.update(
          "update shortlist_item set status='CONTACTED' where id=?", group.get(n).get("id"));
      result.add(
          item(
              group.get(n),
              rels.get(n),
              id,
              invitation,
              status,
              "Cooperation prepared; delivery status is separate from CRM"));
    }
    return result;
  }

  ShortlistOutreachResponse.OutreachItemResult item(
      Map<String, Object> r, UUID rel, UUID deal, UUID invitation, String status, String message) {
    return ShortlistOutreachResponse.OutreachItemResult.builder()
        .shortlistItemId((UUID) r.get("id"))
        .creatorId((UUID) r.get("creator_id"))
        .creatorName(
            Objects.toString(
                r.get("display_name"), Objects.toString(r.get("user_name"), "Creator")))
        .relationshipId(rel)
        .dealId(deal)
        .invitationId(invitation)
        .contactStatus(status)
        .message(message)
        .build();
  }
}
