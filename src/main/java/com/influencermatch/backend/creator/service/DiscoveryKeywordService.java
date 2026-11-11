package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import com.influencermatch.backend.creator.dto.*;
import com.influencermatch.backend.creator.enums.KeywordType;
import com.influencermatch.backend.creator.model.DiscoveryKeyword;
import com.influencermatch.backend.creator.repository.DiscoveryKeywordRepository;
import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoveryKeywordService {

  private final DiscoveryKeywordRepository keywordRepository;
  private final CrawlerServiceClient crawlerServiceClient;

  @Transactional(readOnly = true)
  public List<DiscoveryKeywordDto> listKeywords(
      KeywordType type, String category, Boolean isActive, String query) {
    Specification<DiscoveryKeyword> spec = Specification.where(null);

    if (type != null) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("keywordType"), type));
    }
    if (category != null && !category.isBlank()) {
      spec = spec.and((root, q, cb) -> cb.equal(cb.upper(root.get("category")), category.trim().toUpperCase()));
    }
    if (isActive != null) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("isActive"), isActive));
    }
    if (query != null && !query.isBlank()) {
      String pattern = "%" + query.trim().toLowerCase() + "%";
      spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("keyword")), pattern));
    }

    return keywordRepository.findAll(spec).stream()
        .map(this::mapToDto)
        .toList();
  }

  @Transactional
  public DiscoveryKeywordDto createKeyword(CreateDiscoveryKeywordRequest request) {
    String cleanKw = request.getKeyword().trim();
    String platform = (request.getPlatform() != null && !request.getPlatform().isBlank())
        ? request.getPlatform().trim().toUpperCase() : "TIKTOK";

    if (keywordRepository.existsByKeywordIgnoreCaseAndPlatformIgnoreCase(cleanKw, platform)) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Từ khóa này đã tồn tại trên nền tảng " + platform);
    }

    DiscoveryKeyword entity = DiscoveryKeyword.builder()
        .keyword(cleanKw)
        .category(request.getCategory() != null ? request.getCategory().trim().toUpperCase() : "GENERAL")
        .keywordType(request.getKeywordType() != null ? request.getKeywordType() : KeywordType.TARGET)
        .platform(platform)
        .isActive(request.getIsActive() != null ? request.getIsActive() : true)
        .creatorCount(0L)
        .build();

    return mapToDto(keywordRepository.save(entity));
  }

  @Transactional
  public DiscoveryKeywordDto updateKeyword(UUID id, UpdateDiscoveryKeywordRequest request) {
    DiscoveryKeyword entity = keywordRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy từ khóa: " + id));

    if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
      entity.setKeyword(request.getKeyword().trim());
    }
    if (request.getCategory() != null) {
      entity.setCategory(request.getCategory().trim().toUpperCase());
    }
    if (request.getKeywordType() != null) {
      entity.setKeywordType(request.getKeywordType());
    }
    if (request.getPlatform() != null && !request.getPlatform().isBlank()) {
      entity.setPlatform(request.getPlatform().trim().toUpperCase());
    }
    if (request.getIsActive() != null) {
      entity.setIsActive(request.getIsActive());
    }

    return mapToDto(keywordRepository.save(entity));
  }

  @Transactional
  public DiscoveryKeywordDto toggleKeyword(UUID id) {
    DiscoveryKeyword entity = keywordRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy từ khóa: " + id));

    entity.setIsActive(!Boolean.TRUE.equals(entity.getIsActive()));
    return mapToDto(keywordRepository.save(entity));
  }

  @Transactional
  public void deleteKeyword(UUID id) {
    if (!keywordRepository.existsById(id)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy từ khóa: " + id);
    }
    keywordRepository.deleteById(id);
  }

  @Transactional
  public Map<String, Object> triggerCrawlForKeyword(UUID id, Integer maxChannels) {
    DiscoveryKeyword entity = keywordRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy từ khóa: " + id));

    int count = maxChannels != null && maxChannels > 0 ? maxChannels : 30;
    Optional<Map<String, Object>> resultOpt =
        crawlerServiceClient.triggerKeywordDiscovery(List.of(entity.getKeyword()), count);

    entity.setLastCrawledAt(LocalDateTime.now());
    keywordRepository.save(entity);

    return resultOpt.orElse(Map.of(
        "status", "QUEUED",
        "message", "Đã gửi lệnh cào cho từ khóa: " + entity.getKeyword(),
        "keyword", entity.getKeyword()
    ));
  }

  private DiscoveryKeywordDto mapToDto(DiscoveryKeyword entity) {
    return DiscoveryKeywordDto.builder()
        .id(entity.getId())
        .keyword(entity.getKeyword())
        .category(entity.getCategory())
        .keywordType(entity.getKeywordType())
        .platform(entity.getPlatform())
        .isActive(entity.getIsActive())
        .creatorCount(entity.getCreatorCount())
        .lastCrawledAt(entity.getLastCrawledAt())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }
}

