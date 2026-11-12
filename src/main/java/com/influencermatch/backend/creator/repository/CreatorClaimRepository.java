package com.influencermatch.backend.creator.repository;
import com.influencermatch.backend.creator.model.CreatorClaim;
import com.influencermatch.backend.creator.enums.CreatorClaimStatus;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
public interface CreatorClaimRepository extends JpaRepository<CreatorClaim,UUID>,JpaSpecificationExecutor<CreatorClaim> {
  boolean existsByClaimantIdAndStatus(UUID userId,CreatorClaimStatus status);
  boolean existsByCreatorIdAndClaimantIdAndStatus(UUID creatorId,UUID claimantId,CreatorClaimStatus status);
  List<CreatorClaim> findByCreatorIdAndStatus(UUID creatorId,CreatorClaimStatus status);
  Optional<CreatorClaim> findFirstByCreatorIdAndStatus(UUID creatorId,CreatorClaimStatus status);
}
