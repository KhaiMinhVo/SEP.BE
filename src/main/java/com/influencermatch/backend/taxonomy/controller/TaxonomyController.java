package com.influencermatch.backend.taxonomy.controller;

import com.influencermatch.backend.taxonomy.service.NicheService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/taxonomies")
@RequiredArgsConstructor
public class TaxonomyController {
  private final NicheService service;

  // Preserve the existing string[] contract for discovery clients.
  @GetMapping("/niches")
  public ResponseEntity<List<String>> getNiches() {
    return ResponseEntity.ok(service.activeNames());
  }
}
