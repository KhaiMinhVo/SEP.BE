package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.creator.client.CrawlerServiceClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringJUnitWebConfig(DataManagerCrawlerControllerTest.Config.class)
class DataManagerCrawlerControllerTest {
  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @EnableMethodSecurity
  static class Config {
    @Bean CrawlerServiceClient crawler() { return mock(CrawlerServiceClient.class); }
    @Bean DataManagerCrawlerController controller(CrawlerServiceClient crawler) {
      return new DataManagerCrawlerController(crawler);
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
      return http.csrf(csrf -> csrf.disable())
          .authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
    }
  }
  @Autowired WebApplicationContext context;
  @Autowired CrawlerServiceClient crawler;
  MockMvc mvc() {
    return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test @WithMockUser(roles = "DATA_MANAGER")
  void managerCanReadOverview() throws Exception {
    when(crawler.getOverview(20)).thenReturn(Map.of("total", 3));
    mvc().perform(get("/data/crawler/overview"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
  }

  @Test @WithMockUser(roles = "ADMIN")
  void adminCannotUseManagerCrawler() throws Exception {
    mvc().perform(get("/data/crawler/overview")).andExpect(status().isForbidden());
    mvc().perform(post("/data/crawler/test-scrape").contentType(MediaType.APPLICATION_JSON)
        .content("{\"profileUrl\":\"sample\"}")).andExpect(status().isForbidden());
    mvc().perform(post("/data/crawler/batch-crawl").contentType(MediaType.APPLICATION_JSON)
        .content("{}")).andExpect(status().isForbidden());
  }

  @Test @WithMockUser(roles = "DATA_MANAGER")
  void obsoleteAndAdminRoutesAreRemoved() throws Exception {
    for (String path : List.of("/admin/crawler/overview", "/admin/crawler/metrics",
        "/admin/crawler/categories", "/admin/crawler/creator/sample",
        "/data/crawler/metrics", "/data/crawler/categories", "/data/crawler/creator/sample")) {
      mvc().perform(get(path)).andExpect(status().isNotFound());
    }
    for (String path : List.of("/admin/crawler/test-scrape", "/admin/crawler/batch-crawl",
        "/admin/crawler/retry", "/data/crawler/retry")) {
      mvc().perform(post(path)).andExpect(status().isNotFound());
    }
  }

  @Test @WithMockUser(roles = "DATA_MANAGER")
  void huntForwardsSelectedKeywords() throws Exception {
    when(crawler.batchCrawl("TIKTOK", null, List.of("skincare"), null, 5))
        .thenReturn(Optional.of(Map.of("status", "accepted")));
    mvc().perform(post("/data/crawler/batch-crawl").contentType(MediaType.APPLICATION_JSON)
        .content("{\"platform\":\"TIKTOK\",\"keywords\":[\"skincare\"],\"maxChannels\":5}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("accepted"));
    verify(crawler).batchCrawl("TIKTOK", null, List.of("skincare"), null, 5);
  }

  @Test
  void transportPreservesKeywords() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://crawler.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://crawler.test/batch-crawl"))
        .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json("{\"keywords\":[\"skincare\"],\"max_channels\":5}"))
        .andRespond(withSuccess("{\"status\":\"accepted\"}", MediaType.APPLICATION_JSON));
    new CrawlerServiceClient(builder.build()).batchCrawl("TIKTOK", null, List.of("skincare"), null, 5);
    server.verify();
  }
}
