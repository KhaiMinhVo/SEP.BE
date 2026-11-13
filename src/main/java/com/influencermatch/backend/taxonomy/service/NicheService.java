package com.influencermatch.backend.taxonomy.service;

import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.exception.ErrorCode;
import com.influencermatch.backend.taxonomy.dto.NicheRequest;
import com.influencermatch.backend.taxonomy.model.Niche;
import com.influencermatch.backend.taxonomy.repository.NicheRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NicheService {
  private final NicheRepository repository;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.jdbc.core.JdbcTemplate jdbc;

  public List<Niche> list() { return repository.findAllByOrderByNameAsc(); }

  public List<java.util.Map<String, Object>> getStats() {
    if (jdbc == null) {
      return List.of();
    }
    String sql = """
        SELECT 
            BTRIM(part) AS niche,
            COUNT(DISTINCT metric.creator_id) AS creator_count
        FROM public_creator_metric metric
        CROSS JOIN LATERAL unnest(string_to_array(metric.niche, ',')) AS part
        WHERE BTRIM(part) <> '' AND UPPER(BTRIM(part)) NOT IN ('UNKNOWN', 'GENERAL')
        GROUP BY BTRIM(part)
        ORDER BY creator_count DESC
        """;
    try {
      List<java.util.Map<String, Object>> list = jdbc.query(sql, (rs, rowNum) -> {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        String name = rs.getString("niche");
        long count = rs.getLong("creator_count");
        map.put("niche", name);
        map.put("name", name);
        map.put("creatorCount", count);
        map.put("creator_count", count);
        return map;
      });
      long total = list.stream().mapToLong(m -> ((Number) m.get("creatorCount")).longValue()).sum();
      for (var item : list) {
        long count = ((Number) item.get("creatorCount")).longValue();
        double pct = total > 0 ? Math.round((count * 100.0 / total) * 10.0) / 10.0 : 0.0;
        item.put("percentage", pct);
      }
      return list;
    } catch (Exception e) {
      return List.of();
    }
  }

  @Transactional
  public Niche create(NicheRequest request) {
    String name = request.name().strip();
    if (repository.existsByNameIgnoreCase(name)) throw duplicate();
    return repository.saveAndFlush(apply(new Niche(), request));
  }

  @Transactional
  public Niche update(UUID id, NicheRequest request) {
    Niche niche = find(id);
    if (repository.existsByNameIgnoreCaseAndIdNot(request.name().strip(), id)) throw duplicate();
    return repository.saveAndFlush(apply(niche, request));
  }

  @Transactional
  public void delete(UUID id) { repository.delete(find(id)); }

  private Niche find(UUID id) {
    return repository.findById(id).orElseThrow(() ->
        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy ngành hàng."));
  }

  private BusinessException duplicate() {
    return new BusinessException(ErrorCode.VALIDATION_ERROR, "Tên ngành hàng đã tồn tại.");
  }

  private Niche apply(Niche niche, NicheRequest request) {
    String name = request.name().strip();
    if (name.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Vui lòng nhập tên ngành hàng.");
    niche.setName(name);
    niche.setDescription(request.description() == null ? "" : request.description().strip());
    niche.setActive(Boolean.TRUE.equals(request.active()));
    niche.setKeywords(request.keywords() == null ? List.of() : request.keywords().stream()
        .map(keyword -> keyword.strip().replaceFirst("^#+", "").strip())
        .filter(keyword -> !keyword.isBlank()).distinct().toList());
    return niche;
  }
}
