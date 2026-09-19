package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.controller.*;
import com.influencermatch.backend.creator.dto.*;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.service.*;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PublicCreatorMetricRepository extends JpaRepository<PublicCreatorMetric, UUID> {}
