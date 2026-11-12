package com.influencermatch.backend.taxonomy.controller;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/taxonomies")
public class TaxonomyController {

    @PersistenceContext
    private EntityManager entityManager;

    @GetMapping("/niches")
    @SuppressWarnings("unchecked")
    public ResponseEntity<List<String>> getNiches() {
        // Lay danh sach cac Niche co thuc trong bang public_creator_metric (M2)
        List<String> niches = entityManager.createQuery(
                "SELECT DISTINCT p.niche FROM PublicCreatorMetric p WHERE p.niche IS NOT NULL ORDER BY p.niche ASC"
        ).getResultList();
        
        return ResponseEntity.ok(niches);
    }
}