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

  public List<Niche> list() { return repository.findAllByOrderByNameAsc(); }

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
