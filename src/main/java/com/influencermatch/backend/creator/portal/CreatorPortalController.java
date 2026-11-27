package com.influencermatch.backend.creator.portal;

import static com.influencermatch.backend.creator.portal.PortalDtos.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencermatch.backend.common.dto.ApiResponse;
import com.influencermatch.backend.creator.meta.OAuthFlow;
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
  final com.influencermatch.backend.creator.meta.InstagramUnifiedOAuthProperties instagramUnified;
  final com.influencermatch.backend.creator.tiktok.TikTokLoginProperties tiktok;
  final ChannelOAuthAvailability availability;

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

  @GetMapping("/creators/{id}/rate-cards")
  @PreAuthorize("hasRole('BRAND')")
  @Operation(summary = "Read public Rate Card packages declared by a verified Creator")
  public ResponseEntity<ApiResponse<List<RateCardItem>>> rateCards(@PathVariable UUID id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.ok(projection.rateCards(id)));
  }

  @GetMapping("/creators/portal/accounts/connection-options")
  @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  @Operation(
      summary =
          "Read channel connection options and the availability of each Instagram OAuth method.")
  public ApiResponse<List<ConnectionOption>> connections() {
    profiles.guard(
        com.influencermatch.backend.security.permission.Permission.CLAIM_CREATOR_PROFILE);
    OAuthFlow instagramFlow = availability.instagramFlow();
    var instagramMethods = List.of(
        instagramMethod(OAuthFlow.INSTAGRAM_LOGIN, instagram::requireConfigured),
        instagramMethod(OAuthFlow.FACEBOOK_LOGIN, () -> meta.requireConfigured("INSTAGRAM")),
        instagramMethod(OAuthFlow.INSTAGRAM_UNIFIED_LOGIN, instagramUnified::requireConfigured));
    var preferred = instagramMethods.stream()
        .filter(method -> instagramFlow != null && method.oauthFlow().equals(instagramFlow.name()))
        .findFirst()
        .orElse(new ConnectionMethod(null, false, "NOT_ENABLED_FOR_ENVIRONMENT"));
    return ApiResponse.ok(
        List.of(
            option(
                "FACEBOOK",
                "FACEBOOK_LOGIN",
                "OFFICIAL_API",
                availability.facebookPageEnabled(),
                () -> meta.requireConfigured("FACEBOOK"),
                List.of("FACEBOOK_PAGE", "EXISTING_STABLE_ID")),
            new ConnectionOption(
                "INSTAGRAM", preferred.oauthFlow(), "OFFICIAL_API", true, preferred.available(),
                preferred.unavailableReason(), List.of("PROFESSIONAL_ACCOUNT", "EXISTING_STABLE_ID"),
                instagramMethods),
            option(
                "TIKTOK",
                "TIKTOK_LOGIN",
                "SANDBOX",
                availability.tiktokSandboxEnabled(),
                tiktok::requireConfigured,
                List.of("SANDBOX_TARGET_USER", "CRAWLER_RESOLVER"))));
  }

  static ConnectionOption option(
      String platform, String flow, String environment, boolean enabled,
      Runnable check, List<String> requirements) {
    var method = method(flow, enabled, check);
    return new ConnectionOption(
        platform, flow, environment, true, method.available(), method.unavailableReason(),
        requirements, null);
  }

  private ConnectionMethod instagramMethod(OAuthFlow flow, Runnable check) {
    return method(flow.name(), availability.allowsInstagram(flow), check);
  }

  private static ConnectionMethod method(String flow, boolean enabled, Runnable check) {
    if (!enabled) {
      return new ConnectionMethod(flow, false, "NOT_ENABLED_FOR_ENVIRONMENT");
    }
    try {
      check.run();
      return new ConnectionMethod(flow, true, null);
    } catch (com.influencermatch.backend.exception.BusinessException e) {
      return new ConnectionMethod(flow, false, "NOT_CONFIGURED");
    }
  }
}
