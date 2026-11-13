package com.influencermatch.backend.taxonomy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.influencermatch.backend.exception.BusinessException;
import com.influencermatch.backend.taxonomy.dto.NicheRequest;
import com.influencermatch.backend.taxonomy.model.Niche;
import com.influencermatch.backend.taxonomy.repository.NicheRepository;
import com.influencermatch.backend.taxonomy.service.NicheService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NicheServiceTest {
  private final NicheRepository repository = mock(NicheRepository.class);
  private final NicheService service = new NicheService(repository);

  @Test void trimsNameAndDefaultsDescriptionWhenCreating() {
    when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    Niche saved = service.create(new NicheRequest("  Làm đẹp  ", null, true));
    assertThat(saved.getName()).isEqualTo("Làm đẹp");
    assertThat(saved.getDescription()).isEmpty();
    assertThat(saved.isActive()).isTrue();
  }

  @Test void duplicateNameIsRejectedBeforeWriting() {
    when(repository.existsByNameIgnoreCase("Beauty")).thenReturn(true);
    assertThatThrownBy(() -> service.create(new NicheRequest(" Beauty ", "", true)))
        .isInstanceOf(BusinessException.class).hasMessageContaining("đã tồn tại");
    verify(repository, never()).saveAndFlush(any());
  }

  @Test void updateCanRenameAndDisableWithoutChangingId() {
    UUID id = UUID.randomUUID();
    Niche row = new Niche(); row.setId(id); row.setName("Old");
    when(repository.findById(id)).thenReturn(Optional.of(row));
    when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    Niche updated = service.update(id, new NicheRequest(" New ", " Description ", false));
    assertThat(updated.getId()).isEqualTo(id);
    assertThat(updated.getName()).isEqualTo("New");
    assertThat(updated.getDescription()).isEqualTo("Description");
    assertThat(updated.isActive()).isFalse();
    verify(repository).existsByNameIgnoreCaseAndIdNot("New", id);
  }

  @Test void missingIdIsRejectedWithoutDeletingAnything() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(id)).isInstanceOf(BusinessException.class);
    verify(repository, never()).delete(any());
  }

  @Test void deletesOnlyTheSelectedCatalogueEntry() {
    UUID id = UUID.randomUUID();
    Niche row = new Niche(); row.setId(id);
    when(repository.findById(id)).thenReturn(Optional.of(row));
    service.delete(id);
    verify(repository).delete(row);
  }
  @Test void savesManagedHuntKeywordsWithoutHashtagsOrDuplicates() {
    when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    Niche saved = service.create(new NicheRequest("Gaming", "", true,
        List.of(" #gaming ", "gaming", " review games ")));
    assertThat(saved.getKeywords()).containsExactly("gaming", "review games");
  }
}
