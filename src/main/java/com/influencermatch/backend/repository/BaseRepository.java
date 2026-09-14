package com.influencermatch.backend.repository;

import com.influencermatch.backend.entity.BaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.UUID;

/**
 * Base repository interface shared by all domain repositories in the
 * InfluencerMatch platform.
 *
 * <p>Extends both {@link JpaRepository} (for standard CRUD and pagination)
 * and {@link JpaSpecificationExecutor} (for type-safe dynamic queries via
 * the Criteria API / Specification pattern).
 *
 * <p>{@link NoRepositoryBean} prevents Spring Data from trying to instantiate
 * this interface as a concrete repository bean.
 *
 * @param <T> The entity type; must extend {@link BaseEntity}.
 */
@NoRepositoryBean
public interface BaseRepository<T extends BaseEntity> extends
        JpaRepository<T, UUID>,
        JpaSpecificationExecutor<T> {
}
