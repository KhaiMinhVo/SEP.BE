package com.influencermatch.backend.brand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencermatch.backend.brand.dto.*;
import com.influencermatch.backend.common.dto.PageResponse;
import com.influencermatch.backend.user.User;
import com.influencermatch.backend.user.Role;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BrandService {
    private final BrandProfileRepository profiles;
    private final BrandContextRepository contexts;
    private final UserRepository users;
    private final ObjectMapper objectMapper;

    @Transactional
    public BrandResponse create(BrandRequest request, Authentication authentication) {
        User actor = actor(authentication);
        User owner = resolveOwner(request.userId(), actor);
        if (profiles.existsByUserId(owner.getId())) {
            throw new ConflictException(ErrorCode.BRAND_ALREADY_EXISTS, "This user already has a brand profile");
        }
        BrandProfile profile = BrandProfile.builder()
                .user(owner).businessName(request.businessName().trim()).build();
        applyProfile(profile, request);
        profile = profiles.save(profile);
        BrandContext context = contexts.save(BrandContext.builder()
                .brandProfile(profile).contextVersion(1).profileContext(profileJson(profile))
                .learnedPatterns(objectMapper.createArrayNode()).evidenceRefs(objectMapper.createArrayNode()).build());
        return response(profile, context);
    }

    @Transactional(readOnly = true)
    public BrandResponse me(Authentication authentication) {
        BrandProfile profile = profiles.findByUserId(actor(authentication).getId())
                .orElseThrow(() -> notFound("Brand profile not found"));
        return loadResponse(profile);
    }

    @Transactional(readOnly = true)
    public PageResponse<BrandResponse> list(Pageable pageable, Authentication authentication) {
        User actor = actor(authentication);
        if (actor.getRole() == Role.ADMIN) return PageResponse.from(profiles.findAll(pageable).map(this::loadResponse));
        if (pageable.getPageNumber() > 0) return new PageResponse<>(List.of(), pageable.getPageNumber(), pageable.getPageSize(), profiles.existsByUserId(actor.getId()) ? 1 : 0, 1);
        List<BrandResponse> items = profiles.findByUserId(actor.getId()).map(p -> List.of(loadResponse(p))).orElseGet(List::of);
        return new PageResponse<>(items, 0, pageable.getPageSize(), items.size(), items.isEmpty() ? 0 : 1);
    }

    @Transactional(readOnly = true)
    public BrandResponse get(UUID profileId, Authentication authentication) {
        BrandProfile profile = requireProfile(profileId);
        requireAccess(profile, actor(authentication));
        return loadResponse(profile);
    }

    @Transactional
    public BrandResponse update(UUID profileId, BrandRequest request, Authentication authentication) {
        BrandProfile profile = requireProfile(profileId);
        requireAccess(profile, actor(authentication));
        profile.setBusinessName(request.businessName().trim());
        applyProfile(profile, request);
        BrandContext context = requireContext(profileId);
        context.updateProfileContext(profileJson(profile));
        return response(profile, context);
    }

    @Transactional(readOnly = true)
    public BrandProfile requireAccessible(UUID profileId, Authentication authentication) {
        BrandProfile profile = requireProfile(profileId);
        requireAccess(profile, actor(authentication));
        return profile;
    }

    private User resolveOwner(UUID requestedId, User actor) {
        UUID ownerId = actor.getRole() == Role.ADMIN ? requestedId : actor.getId();
        if (ownerId == null) throw new ValidationException("userId is required when an admin creates a brand profile");
        if (actor.getRole() != Role.ADMIN && requestedId != null && !requestedId.equals(actor.getId()))
            throw new ForbiddenException("A brand account can only create its own profile");
        User owner = users.findById(ownerId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Owner user not found"));
        if (owner.getRole() != Role.BRAND) throw new BusinessException(ErrorCode.USER_NOT_BRAND, "Brand profile owner must have role BRAND");
        return owner;
    }

    private BrandProfile requireProfile(UUID id) {
        return profiles.findById(id).orElseThrow(() -> notFound("Brand profile not found: " + id));
    }
    private BrandContext requireContext(UUID profileId) {
        return contexts.findByBrandProfileId(profileId).orElseThrow(() -> notFound("Brand context not found"));
    }
    private void requireAccess(BrandProfile profile, User actor) {
        if (actor.getRole() != Role.ADMIN && !profile.getUser().getId().equals(actor.getId()))
            throw new ForbiddenException("You cannot access another user's brand profile");
    }
    private User actor(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        return user;
    }
    private void applyProfile(BrandProfile p, BrandRequest r) {
        p.setIndustry(r.industry()); p.setProductCategories(strings(r.productCategories())); p.setWebsite(r.website()); p.setLocation(r.location());
        p.setTargetMarkets(strings(r.targetMarkets())); p.setTargetAudiences(strings(r.targetAudiences())); p.setBrandTone(r.brandTone());
        p.setPreferredPlatforms(r.preferredPlatforms() == null ? List.of() : r.preferredPlatforms().stream().map(Enum::name).distinct().toList());
        p.setDescription(r.description());
    }
    private List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
    }
    private JsonNode profileJson(BrandProfile p) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("businessName", p.getBusinessName()); value.put("industry", p.getIndustry()); value.put("productCategories", p.getProductCategories());
        value.put("website", p.getWebsite()); value.put("location", p.getLocation()); value.put("targetMarkets", p.getTargetMarkets());
        value.put("targetAudiences", p.getTargetAudiences()); value.put("brandTone", p.getBrandTone()); value.put("preferredPlatforms", p.getPreferredPlatforms());
        value.put("description", p.getDescription());
        return objectMapper.valueToTree(value);
    }
    private BrandResponse loadResponse(BrandProfile profile) { return response(profile, requireContext(profile.getId())); }
    private BrandResponse response(BrandProfile p, BrandContext c) {
        return new BrandResponse(p.getId(), p.getUser().getId(), p.getBusinessName(), p.getIndustry(), p.getProductCategories(),
                p.getWebsite(), p.getLocation(), p.getTargetMarkets(), p.getTargetAudiences(), p.getBrandTone(),
                p.getPreferredPlatforms().stream().map(Platform::valueOf).toList(), p.getDescription(), c.getContextVersion(), p.getCreatedAt(), p.getUpdatedAt());
    }
    private BusinessException notFound(String detail) { return new BusinessException(ErrorCode.BRAND_NOT_FOUND, detail); }
}


