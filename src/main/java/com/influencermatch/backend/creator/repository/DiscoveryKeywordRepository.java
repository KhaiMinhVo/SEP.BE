package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.enums.KeywordType;
import com.influencermatch.backend.creator.model.DiscoveryKeyword;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscoveryKeywordRepository
    extends JpaRepository<DiscoveryKeyword, UUID>, JpaSpecificationExecutor<DiscoveryKeyword> {

  List<DiscoveryKeyword> findByIsActiveTrue();

  List<DiscoveryKeyword> findByIsActiveAndKeywordType(Boolean isActive, KeywordType keywordType);

  List<DiscoveryKeyword> findByKeywordType(KeywordType keywordType);

  boolean existsByKeywordIgnoreCaseAndPlatformIgnoreCase(String keyword, String platform);
}

