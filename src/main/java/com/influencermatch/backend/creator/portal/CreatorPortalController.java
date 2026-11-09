package com.influencermatch.backend.creator.portal;

import static com.influencermatch.backend.creator.portal.PortalDtos.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.security.permission.Permissions;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name = "Creator Portal Profile")
@SecurityRequirement(name = "BearerAuth")
public class CreatorPortalController {
  final CreatorPortalProfiles profiles;
  final CreatorPortalProjection projection;
  final com.influencermatch.backend.creator.meta.MetaOAuthProperties meta;
  final com.influencermatch.backend.creator.meta.InstagramOAuthProperties instagram;
  final com.influencermatch.backend.creator.tiktok.TikTokLoginProperties tiktok;

  ResponseEntity<ApiResponse<Profile>> profile(Profile p) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .eTag(CreatorPortalProfiles.tag(Permissions.actor().getId(), p.profileRevision()))
        .body(ApiResponse.ok(p));
  }

  @GetMapping("/creators/portal/profile")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('VIEW_OWN_PROFILE')")
  @Operation(summary = "Read own declared profile without creating data")
  public ResponseEntity<ApiResponse<Profile>> get() {
    return profile(profiles.get());
  }

  @PatchMapping("/creators/portal/profile")
  @PreAuthorize(
      "hasRole('CREATOR') and hasAuthority('VIEW_OWN_PROFILE') and"
          + " hasAuthority('MANAGE_OWN_CREATOR_PROFILE')")
  @Operation(
      summary = "Patch own profile",
      description =
          "If-Match required; omitted fields preserved, optional null clears, portfolio replaces"
              + " the whole list. No ownership or metrics change.")
  public ResponseEntity<ApiResponse<Profile>> patch(
      @RequestBody JsonNode body,
      @RequestHeader(value = "If-Match", required = false) String match) {
    return profile(profiles.patch(body, match));
  }

  @GetMapping("/creators/{id}/contacts")
  @PreAuthorize("hasRole('BRAND') and hasAuthority('VIEW_CREATOR_CONTACT')")
  @Operation(
      summary = "Read permitted Creator contact fields",
      description =
          "Private fields remain NOT_SHARED. No login email, bank data or provider tokens are"
              + " returned.")
  public ResponseEntity<ApiResponse<SharedContacts>> contacts(@PathVariable UUID id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.ok(projection.contacts(id, true)));
  }

  @GetMapping("/creators/portal/accounts/connection-options")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @Operation(
      summary =
          "Read available OAuth flows without calling providers or exposing configuration secrets")
  public ApiResponse<List<ConnectionOption>> connections() {
    profiles.guard(
        com.influencermatch.backend.security.permission.Permission.CLAIM_CREATOR_PROFILE);
    return ApiResponse.ok(
        List.of(
            option(
                "FACEBOOK",
                "FACEBOOK_LOGIN",
                "OFFICIAL_API",
                meta::requireConfigured,
                List.of("FACEBOOK_PAGE", "EXISTING_STABLE_ID")),
            option(
                "INSTAGRAM",
                "FACEBOOK_LOGIN",
                "OFFICIAL_API",
                meta::requireConfigured,
                List.of("PROFESSIONAL_ACCOUNT", "LINKED_FACEBOOK_PAGE", "EXISTING_STABLE_ID")),
            option(
                "INSTAGRAM",
                "INSTAGRAM_LOGIN",
                "OFFICIAL_API",
                instagram::requireConfigured,
                List.of("PROFESSIONAL_ACCOUNT", "EXISTING_STABLE_ID")),
            option(
                "TIKTOK",
                "TIKTOK_LOGIN",
                "SANDBOX",
                tiktok::requireConfigured,
                List.of("SANDBOX_TARGET_USER", "CRAWLER_RESOLVER")),
            new ConnectionOption(
                "YOUTUBE", "LEGACY_TESTING", "TESTING", false, false, "OUTSIDE_MVP", List.of())));
  }

  static ConnectionOption option(
      String platform, String flow, String environment, Runnable check, List<String> requirements) {
    boolean available = true;
    try {
      check.run();
    } catch (com.influencermatch.backend.exception.BusinessException e) {
      available = false;
    }
    return new ConnectionOption(
        platform,
        flow,
        environment,
        true,
        available,
        available ? null : "NOT_CONFIGURED",
        requirements);
  }
}
