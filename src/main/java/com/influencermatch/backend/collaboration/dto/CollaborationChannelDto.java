package com.influencermatch.backend.collaboration.dto;

import java.util.UUID;

public record CollaborationChannelDto(
    UUID creatorId, UUID relationshipId, UUID masterId, String platform, String username) {}
