package com.influencermatch.backend.creator.controller;

import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.model.Creator;
import com.influencermatch.backend.creator.repository.CreatorRepository;
import com.influencermatch.backend.exception.NotFoundException;
import com.influencermatch.backend.storage.CloudinaryStorageService;
import com.influencermatch.backend.storage.StoredMedia;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/creators")
@RequiredArgsConstructor
@Tag(name = "Creator Avatar", description = "Endpoints for creator avatar uploads to cloud storage")
public class CreatorAvatarController {

  private final CreatorRepository creatorRepository;
  private final CloudinaryStorageService cloudinaryStorageService;

  @PostMapping(value = "/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@creatorSecurity.isOwner(#id) or hasRole('ADMIN')")
  @Transactional
  @Operation(
      summary = "Upload creator profile avatar",
      description = "Upload an avatar directly to cloud storage (Cloudinary) and link it permanently to the creator.")
  public ApiResponse<StoredMedia> uploadAvatar(
      @PathVariable UUID id,
      @RequestPart("file") MultipartFile file) {

    Creator creator = creatorRepository.findById(id)
        .orElseThrow(() -> new NotFoundException("Creator not found with id: " + id));

    String oldStorageKey = creator.getAvatarStorageKey();

    String prefix = creator.getUserName() != null ? creator.getUserName() : id.toString();
    StoredMedia stored = cloudinaryStorageService.uploadFile(
        file,
        CloudinaryStorageService.FOLDER_CREATOR_AVATAR,
        prefix
    );

    creator.setAvatarUrl(stored.url());
    creator.setAvatarStorageKey(stored.storageKey());
    creator.setAvatarMimeType(stored.mimeType());
    creator.setAvatarSizeBytes(stored.sizeBytes());
    creator.setAvatarUpdatedAt(stored.updatedAt());
    creatorRepository.save(creator);

    // Clean up previous cloud file if exists
    if (oldStorageKey != null && !oldStorageKey.isBlank() && !oldStorageKey.equals(stored.storageKey())) {
      cloudinaryStorageService.deleteFile(oldStorageKey);
    }

    log.info("Successfully updated avatar for creator {}: url={}", creator.getUserName(), stored.url());
    return ApiResponse.ok(stored);
  }
}
