package com.influencermatch.backend.creator;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PublicCreatorMetricRepository extends JpaRepository<PublicCreatorMetric, UUID> {
}
