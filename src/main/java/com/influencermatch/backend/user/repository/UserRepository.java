package com.influencermatch.backend.user.repository;
import com.influencermatch.backend.user.model.*;
import com.influencermatch.backend.user.repository.*;
import com.influencermatch.backend.user.service.*;
import com.influencermatch.backend.user.enums.*;
import com.influencermatch.backend.user.dto.*;
import com.influencermatch.backend.user.controller.*;

import com.influencermatch.backend.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}


