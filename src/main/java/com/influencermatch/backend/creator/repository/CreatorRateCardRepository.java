package com.influencermatch.backend.creator.repository;
import com.influencermatch.backend.creator.model.CreatorRateCard;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CreatorRateCardRepository extends JpaRepository<CreatorRateCard,UUID> {
  @org.springframework.data.jpa.repository.Query("""
      select r from CreatorRateCard r join fetch r.claim claim join fetch claim.creator c
      where c.id in :ids and c.sourceType=com.influencermatch.backend.creator.enums.CreatorSourceType.AUTHORIZED
      and claim.status=com.influencermatch.backend.creator.enums.CreatorClaimStatus.APPROVED
      and c.master.user.id=claim.claimant.id order by r.contentType
      """)
  List<CreatorRateCard> visibleForCreators(@org.springframework.data.repository.query.Param("ids") Collection<UUID> ids);
  List<CreatorRateCard> findByClaimIdOrderByContentTypeAsc(UUID claimId);
  void deleteByClaimId(UUID claimId);
}
