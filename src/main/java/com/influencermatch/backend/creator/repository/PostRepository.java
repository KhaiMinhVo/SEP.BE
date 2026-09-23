package com.influencermatch.backend.creator.repository;

import com.influencermatch.backend.creator.model.Post;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PostRepository extends JpaRepository<Post, UUID> {
    List<Post> findByCreatorId(UUID creatorId);
    List<Post> findByCreatorIdIn(java.util.Collection<UUID> creatorIds);
    void deleteByCreatorId(UUID creatorId);
}
