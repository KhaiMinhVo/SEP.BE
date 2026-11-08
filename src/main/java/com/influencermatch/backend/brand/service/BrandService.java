package com.influencermatch.backend.brand.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.controller.*;
import com.influencermatch.backend.brand.dto.*;
import com.influencermatch.backend.brand.enums.*;
import com.influencermatch.backend.brand.model.*;
import com.influencermatch.backend.brand.repository.*;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.security.permission.*;
import com.influencermatch.backend.user.enums.Role;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BrandService {
  private final BrandProfileRepository profiles;
  private final BrandContextRepository contexts;
  private final UserRepository users;
  private final ObjectMapper objectMapper;
  @org.springframework.beans.factory.annotation.Autowired private BrandContextLifecycle lifecycle;

  @org.springframework.beans.factory.annotation.Autowired
  private com.influencermatch.backend.brand.embedding.BrandEmbeddingStore embeddings;

  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

  @org.springframework.beans.factory.annotation.Autowired
  private jakarta.validation.Validator validator;

  @org.springframework.beans.factory.annotation.Autowired
  private java.time.Clock clock = java.time.Clock.systemUTC();

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('CREATE_BRAND_PROFILE')")
  public BrandResponse create(BrandRequest request, Authentication authentication) {
    Permissions.require(Permission.CREATE_BRAND_PROFILE);
    users.lockRoleChanges();
    User actor = actor(authentication);
    User owner = resolveOwner(request.userId(), actor);
    if (profiles.existsByUserId(owner.getId())) {
      throw new ConflictException(
          ErrorCode.BRAND_ALREADY_EXISTS, "This user already has a brand profile");
    }
    BrandProfile profile =
        BrandProfile.builder().user(owner).businessName(request.businessName().trim()).build();
    applyProfile(profile, request);
    profile = profiles.save(profile);
    BrandContext context = lifecycle.initial(profile);
    return response(profile, context);
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('VIEW_OWN_BRAND_DATA')")
  public BrandResponse me(Authentication authentication) {
    com.influencermatch.backend.security.permission.Permissions.require(
        com.influencermatch.backend.security.permission.Permission.VIEW_OWN_BRAND_DATA);
    BrandProfile profile =
        profiles
            .findByUserId(actor(authentication).getId())
            .orElseThrow(() -> notFound("Brand profile not found"));
    return loadResponse(profile);
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_OWN_BRAND_DATA') or hasAuthority('VIEW_BRAND_SUPPORT_DATA')")
  public PageResponse<BrandResponse> list(Pageable pageable, Authentication authentication) {
    com.influencermatch.backend.security.permission.Permissions.require(
        com.influencermatch.backend.security.permission.Permission.VIEW_OWN_BRAND_DATA,
        com.influencermatch.backend.security.permission.Permission.VIEW_BRAND_SUPPORT_DATA);
    User actor = actor(authentication);
    if (actor.getRole() == Role.ADMIN)
      return PageResponse.from(profiles.findAll(pageable).map(this::loadResponse));
    if (pageable.getPageNumber() > 0)
      return new PageResponse<>(
          List.of(),
          pageable.getPageNumber(),
          pageable.getPageSize(),
          profiles.existsByUserId(actor.getId()) ? 1 : 0,
          1);
    List<BrandResponse> items =
        profiles.findByUserId(actor.getId()).map(p -> List.of(loadResponse(p))).orElseGet(List::of);
    return new PageResponse<>(
        items, 0, pageable.getPageSize(), items.size(), items.isEmpty() ? 0 : 1);
  }

  @Transactional(readOnly = true)
  @org.springframework.security.access.prepost.PreAuthorize(
      "hasAuthority('VIEW_OWN_BRAND_DATA') or hasAuthority('VIEW_BRAND_SUPPORT_DATA')")
  public BrandResponse get(UUID profileId, Authentication authentication) {
    com.influencermatch.backend.security.permission.Permissions.require(
        com.influencermatch.backend.security.permission.Permission.VIEW_OWN_BRAND_DATA,
        com.influencermatch.backend.security.permission.Permission.VIEW_BRAND_SUPPORT_DATA);
    BrandProfile profile = requireProfile(profileId);
    requireAccess(profile, actor(authentication));
    return loadResponse(profile);
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('UPDATE_BRAND_PROFILE')")
  public BrandResponse updateMe(
      BrandRequest request, Authentication authentication, String revision) {
    Permissions.require(Permission.UPDATE_BRAND_PROFILE);
    var p =
        profiles
            .findByUserId(actor(authentication).getId())
            .orElseThrow(() -> notFound("Brand profile not found"));
    return update(p.getId(), request, authentication, revision);
  }

  public BrandResponse updateMe(BrandRequest request, Authentication auth) {
    return updateMe(request, auth, null);
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('UPDATE_BRAND_PROFILE')")
  public BrandResponse update(UUID id, BrandRequest request, Authentication auth, String revision) {
    var p = locked(id, auth);
    BrandRevision.require(revision, id, p.getProfileRevision());
    return applyUpdate(p, request);
  }

  public BrandResponse update(UUID id, BrandRequest request, Authentication auth) {
    return update(id, request, auth, null);
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('UPDATE_BRAND_PROFILE')")
  public BrandResponse patchMe(
      com.fasterxml.jackson.databind.JsonNode patch, Authentication auth, String revision) {
    Permissions.require(Permission.UPDATE_BRAND_PROFILE);
    var existing =
        profiles
            .findByUserId(actor(auth).getId())
            .orElseThrow(() -> notFound("Brand profile not found"));
    var p = locked(existing.getId(), auth);
    BrandRevision.require(revision, p.getId(), p.getProfileRevision());
    if (patch == null || !patch.isObject())
      throw new ValidationException("PATCH requires an object");
    var data =
        (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.valueToTree(request(p));
    Set<String> allowed =
        Set.of(
            "businessName",
            "phoneNumber",
            "industry",
            "productCategories",
            "website",
            "location",
            "targetMarkets",
            "targetAudiences",
            "brandTone",
            "preferredPlatforms",
            "description",
            "positioning",
            "guidelines");
    patch
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!allowed.contains(k))
                throw new ValidationException("Unsupported Brand field: " + k);
            });
    Set<String> lists =
        Set.of("productCategories", "targetMarkets", "targetAudiences", "preferredPlatforms");
    patch
        .fields()
        .forEachRemaining(
            e -> {
              var v = e.getValue();
              if (!v.isNull()) {
                if (lists.contains(e.getKey())) {
                  if (!v.isArray()) throw new ValidationException("Expected a list: " + e.getKey());
                  v.forEach(
                      item -> {
                        if (!item.isTextual())
                          throw new ValidationException("Expected string list items");
                      });
                } else if (!v.isTextual())
                  throw new ValidationException("Expected a string: " + e.getKey());
              }
              data.set(e.getKey(), v);
            });
    BrandRequest r;
    try {
      r = objectMapper.treeToValue(data, BrandRequest.class);
    } catch (Exception ex) {
      throw new ValidationException("Invalid Brand field type or platform");
    }
    var errors = validator.validate(r);
    if (!errors.isEmpty())
      throw new ValidationException(
          "Invalid Brand fields: "
              + errors.stream().map(e -> e.getPropertyPath().toString()).sorted().toList());
    return applyUpdate(p, r);
  }

  private BrandResponse applyUpdate(BrandProfile p, BrandRequest r) {
    var before = objectMapper.valueToTree(request(p));
    var semanticBefore = BrandContextData.of(p, objectMapper);
    p.setBusinessName(r.businessName().trim());
    applyProfile(p, r);
    if ("COMPLETED".equals(p.getOnboardingStatus())) validateComplete(p);
    if (before.equals(objectMapper.valueToTree(request(p)))) return loadResponse(p);
    p.setProfileRevision(p.getProfileRevision() + 1);
    profiles.save(p);
    var current = requireContext(p.getId());
    var c =
        semanticBefore.equals(BrandContextData.of(p, objectMapper))
            ? current
            : lifecycle.append(p, current.getContextVersion() + 1);
    return response(p, c);
  }

  private BrandRequest request(BrandProfile p) {
    return new BrandRequest(
        null,
        p.getBusinessName(),
        p.getIndustry(),
        p.getProductCategories(),
        p.getWebsite(),
        p.getLocation(),
        p.getTargetMarkets(),
        p.getTargetAudiences(),
        p.getBrandTone(),
        p.getPreferredPlatforms().stream().map(Platform::valueOf).toList(),
        p.getDescription(),
        p.getPhoneNumber(),
        p.getPositioning(),
        p.getGuidelines());
  }

  private BrandProfile locked(UUID id, Authentication auth) {
    Permissions.require(Permission.UPDATE_BRAND_PROFILE);
    users.lockRoleChanges();
    users.findLockedByEmail(actor(auth).getEmail());
    var p = profiles.lockForBilling(id).orElseThrow(() -> notFound("Brand profile not found"));
    em.refresh(p);
    requireAccess(p, actor(auth));
    if (actor(auth).getRole() != Role.BRAND) throw new ForbiddenException("Brand required");
    return p;
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('UPDATE_BRAND_PROFILE')")
  public BrandResponse retryEmbedding(Authentication auth) {
    var p =
        profiles
            .findByUserId(actor(auth).getId())
            .orElseThrow(() -> notFound("Brand profile not found"));
    p = locked(p.getId(), auth);
    var c = requireContext(p.getId());
    embeddings.retry(c.getId());
    return response(p, c);
  }

  @Transactional(readOnly = true)
  public BrandProfile requireAccessible(UUID profileId, Authentication authentication) {
    BrandProfile profile = requireProfile(profileId);
    requireAccess(profile, actor(authentication));
    return profile;
  }

  @Transactional
  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('UPDATE_BRAND_PROFILE')")
  public BrandResponse complete(Authentication authentication, String revision) {
    var existing =
        profiles
            .findByUserId(actor(authentication).getId())
            .orElseThrow(() -> notFound("Brand profile not found"));
    var p = locked(existing.getId(), authentication);
    BrandRevision.require(revision, p.getId(), p.getProfileRevision());
    validateComplete(p);
    if (!"COMPLETED".equals(p.getOnboardingStatus())) {
      p.setProfileRevision(p.getProfileRevision() + 1);
      p.setOnboardingStatus("COMPLETED");
      p.setOnboardingCompletedAt(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
      profiles.save(p);
    }
    return loadResponse(p);
  }

  public BrandResponse complete(Authentication auth) {
    return complete(auth, null);
  }

  private void validateComplete(BrandProfile p) {
    var missing = new LinkedHashMap<String, List<String>>();
    if (p.getBusinessName() == null || p.getBusinessName().isBlank())
      missing.put("businessName", List.of("Required"));
    if (p.getPhoneNumber() == null || p.getPhoneNumber().isBlank())
      missing.put("phoneNumber", List.of("Required"));
    if (p.getIndustry() == null || p.getIndustry().isBlank())
      missing.put("industry", List.of("Required"));
    if (p.getBrandTone() == null || p.getBrandTone().isBlank())
      missing.put("brandTone", List.of("Required"));
    if (p.getProductCategories() == null || p.getProductCategories().isEmpty())
      missing.put("productCategories", List.of("At least one required"));
    if (p.getTargetAudiences() == null || p.getTargetAudiences().isEmpty())
      missing.put("targetAudiences", List.of("At least one required"));
    if (p.getPreferredPlatforms() == null || p.getPreferredPlatforms().isEmpty())
      missing.put("preferredPlatforms", List.of("At least one required"));
    if (!missing.isEmpty())
      throw new com.influencermatch.backend.exception.ProfileIncompleteException(missing);
  }

  private User resolveOwner(UUID requestedId, User actor) {
    UUID ownerId = actor.getRole() == Role.ADMIN ? requestedId : actor.getId();
    if (ownerId == null)
      throw new ValidationException("userId is required when an admin creates a brand profile");
    if (actor.getRole() != Role.ADMIN && requestedId != null && !requestedId.equals(actor.getId()))
      throw new ForbiddenException("A brand account can only create its own profile");
    User owner =
        users
            .findById(ownerId)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Owner user not found"));
    if (owner.getRole() != Role.BRAND)
      throw new BusinessException(
          ErrorCode.USER_NOT_BRAND, "Brand profile owner must have role BRAND");
    return owner;
  }

  private BrandProfile requireProfile(UUID id) {
    return profiles.findById(id).orElseThrow(() -> notFound("Brand profile not found: " + id));
  }

  private BrandContext requireContext(UUID profileId) {
    return contexts
        .findByBrandProfileId(profileId)
        .orElseThrow(() -> notFound("Brand context not found"));
  }

  private void requireAccess(BrandProfile profile, User actor) {
    if (!(actor.getRole() == Role.ADMIN
            && RolePermissions.forRole(actor.getRole())
                .contains(Permission.VIEW_BRAND_SUPPORT_DATA))
        && !(actor.getRole() == Role.BRAND && profile.getUser().getId().equals(actor.getId())))
      throw new ForbiddenException("You cannot access another user's brand profile");
  }

  private User actor(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof User user))
      throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
    return user;
  }

  private void applyProfile(BrandProfile p, BrandRequest r) {
    p.setPhoneNumber(
        r.phoneNumber() == null
            ? null
            : com.influencermatch.backend.auth.service.RegistrationSupport.phone(r.phoneNumber()));
    p.setIndustry(BrandContextData.text(r.industry()));
    p.setPositioning(BrandContextData.text(r.positioning()));
    p.setGuidelines(BrandContextData.text(r.guidelines()));
    p.setProductCategories(strings(r.productCategories()));
    p.setWebsite(BrandContextData.text(r.website()));
    p.setLocation(BrandContextData.text(r.location()));
    p.setTargetMarkets(strings(r.targetMarkets()));
    p.setTargetAudiences(strings(r.targetAudiences()));
    p.setBrandTone(BrandContextData.text(r.brandTone()));
    p.setPreferredPlatforms(
        r.preferredPlatforms() == null
            ? List.of()
            : r.preferredPlatforms().stream().map(Enum::name).distinct().sorted().toList());
    p.setDescription(BrandContextData.text(r.description()));
  }

  private List<String> strings(List<String> values) {
    return BrandContextData.list(values);
  }

  private BrandResponse loadResponse(BrandProfile profile) {
    return response(profile, requireContext(profile.getId()));
  }

  private BrandResponse response(BrandProfile p, BrandContext c) {
    var view = embeddings.view(c.getId());
    return new BrandResponse(
        p.getId(),
        p.getUser().getId(),
        p.getBusinessName(),
        p.getIndustry(),
        p.getProductCategories(),
        p.getWebsite(),
        p.getLocation(),
        p.getTargetMarkets(),
        p.getTargetAudiences(),
        p.getBrandTone(),
        p.getPreferredPlatforms().stream().map(Platform::valueOf).toList(),
        p.getDescription(),
        c.getContextVersion(),
        p.getCreatedAt(),
        p.getUpdatedAt(),
        p.getPhoneNumber(),
        p.getOnboardingStatus(),
        p.getOnboardingCompletedAt(),
        p.getProfileRevision(),
        p.getPositioning(),
        p.getGuidelines(),
        view.status(),
        view.model(),
        view.modelVersion(),
        view.readyAt());
  }

  private BusinessException notFound(String detail) {
    return new BusinessException(ErrorCode.BRAND_NOT_FOUND, detail);
  }
}
