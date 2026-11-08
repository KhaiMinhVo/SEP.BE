package com.influencermatch.backend.brand.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.embedding.BrandEmbeddingStore;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.repository.BrandContextRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class BrandContextLifecycle {
  private final BrandContextRepository contexts;
  private final ObjectMapper mapper;
  private final BrandEmbeddingStore embeddings;

  @Transactional(propagation = Propagation.MANDATORY)
  public BrandContext initial(BrandProfile profile) {
    return append(profile, 1);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public BrandContext append(BrandProfile profile, int version) {
    var c =
        contexts.saveAndFlush(
            BrandContext.builder()
                .brandProfile(profile)
                .contextVersion(version)
                .profileContext(BrandContextData.of(profile, mapper))
                .learnedPatterns(mapper.createArrayNode())
                .evidenceRefs(mapper.createArrayNode())
                .build());
    embeddings.enqueue(c.getId());
    return c;
  }
}
