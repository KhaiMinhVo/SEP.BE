package com.influencermatch.backend.creator.service;

import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import com.influencermatch.backend.creator.model.DiscoveryKeyword;
import com.influencermatch.backend.creator.repository.DiscoveryKeywordRepository;
import com.influencermatch.backend.exception.BusinessException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveryKeywordServiceTest {
  @Test
  void unavailableOrRejectedCrawlerNeverReportsQueuedOrUpdatesTimestamp() {
    for (var response : List.of(Optional.<Map<String, Object>>empty(),
        Optional.of(Map.<String, Object>of("status", "error")))) {
      var repository = mock(DiscoveryKeywordRepository.class);
      var crawler = mock(CrawlerServiceClient.class);
      var id = UUID.randomUUID();
      var keyword = new DiscoveryKeyword();
      keyword.setKeyword("tech");
      when(repository.findById(id)).thenReturn(Optional.of(keyword));
      when(crawler.triggerKeywordDiscovery(List.of("tech"), 5)).thenReturn(response);
      var service = new DiscoveryKeywordService(repository, crawler);
      assertThrows(BusinessException.class, () -> service.triggerCrawlForKeyword(id, 5));
      assertNull(keyword.getLastCrawledAt());
      verify(repository, never()).save(any());
    }
  }

  @Test
  void queueAcknowledgementDoesNotClaimCrawlCompleted() {
    var repository = mock(DiscoveryKeywordRepository.class);
    var crawler = mock(CrawlerServiceClient.class);
    var id = UUID.randomUUID();
    var keyword = new DiscoveryKeyword();
    keyword.setKeyword("tech");
    var response = Map.<String, Object>of("status", "QUEUED");
    when(repository.findById(id)).thenReturn(Optional.of(keyword));
    when(crawler.triggerKeywordDiscovery(List.of("tech"), 5)).thenReturn(Optional.of(response));
    assertEquals(response, new DiscoveryKeywordService(repository, crawler).triggerCrawlForKeyword(id, 5));
    assertNull(keyword.getLastCrawledAt());
    verify(repository, never()).save(any());
  }
}
