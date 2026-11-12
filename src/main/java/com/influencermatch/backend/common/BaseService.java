package com.influencermatch.backend.common;

import com.influencermatch.backend.exception.ResourceNotFoundException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Abstract base service providing common CRUD operations for all domain services in the
 * InfluencerMatch platform.
 *
 * <p>Concrete service implementations should extend this class and inject their specific repository
 * through the constructor, calling {@code super(repository)}.
 *
 * <p>This class is intentionally <strong>not</strong> annotated with {@code @Service} or
 * {@code @Transactional}. Transaction boundaries and Spring bean configuration are the
 * responsibility of concrete subclasses, giving them full control over transaction semantics.
 *
 * @param <T> The entity type managed by this service; must extend {@link BaseEntity}.
 * @param <R> The repository type for {@code T}; must extend {@link BaseRepository}.
 */
@Slf4j
public abstract class BaseService<T extends BaseEntity, R extends BaseRepository<T>> {

  /** The repository delegate used for all data access operations. */
  protected final R repository;

  /**
   * Constructs a new {@code BaseService} with the supplied repository.
   *
   * @param repository The Spring Data JPA repository for entity {@code T}.
   */
  protected BaseService(R repository) {
    this.repository = repository;
  }

  //  Read Operations

  /**
   * Retrieves an entity by its UUID primary key.
   *
   * @param id The UUID of the entity.
   * @return The found entity.
   * @throws ResourceNotFoundException if no entity with the given ID exists.
   */
  public T findById(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(
            () -> {
              log.warn("Entity not found with id: {}", id);
              return new ResourceNotFoundException(getEntityName(), "id", id);
            });
  }

  /**
   * Returns a paginated list of all entities of type {@code T}.
   *
   * @param pageable Pagination and sorting parameters.
   * @return A {@link Page} of entities.
   */
  public Page<T> findAll(Pageable pageable) {
    return repository.findAll(pageable);
  }

  //  Write Operations

  /**
   * Persists a new entity or merges an existing one.
   *
   * @param entity The entity to save.
   * @return The persisted entity (with generated ID and audit timestamps populated).
   */
  public T save(T entity) {
    return repository.save(entity);
  }

  /**
   * Deletes an entity by its UUID primary key.
   *
   * @param id The UUID of the entity to delete.
   * @throws ResourceNotFoundException if no entity with the given ID exists.
   */
  public void deleteById(UUID id) {
    T entity = findById(id); // validates existence first
    repository.delete(entity);
    log.info("Deleted {} with id: {}", getEntityName(), id);
  }

  //  Utility

  /**
   * Returns the simple class name of the managed entity for use in log messages and exception
   * details.
   *
   * <p>Override in subclasses to return a more user-friendly name if needed.
   *
   * @return The entity's simple class name (e.g. "User", "Campaign").
   */
  protected String getEntityName() {
    return "Entity";
  }
}
