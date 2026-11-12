package com.influencermatch.backend.taxonomy.repository;

import com.influencermatch.backend.taxonomy.model.Niche;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NicheRepository extends JpaRepository<Niche, UUID> {
  List<Niche> findAllByOrderByNameAsc();
  List<Niche> findByActiveTrueOrderByNameAsc();
  boolean existsByNameIgnoreCase(String name);
  boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
