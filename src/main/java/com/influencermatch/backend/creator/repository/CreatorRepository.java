package com.influencermatch.backend.creator.repository;
import com.influencermatch.backend.creator.model.*;
import com.influencermatch.backend.creator.repository.*;
import com.influencermatch.backend.creator.service.*;
import com.influencermatch.backend.creator.enums.*;
import com.influencermatch.backend.creator.dto.*;
import com.influencermatch.backend.creator.controller.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreatorRepository extends JpaRepository<Creator, UUID> {
    Optional<Creator> findByPlatformAndExternalId(String platform, String externalId);
}
