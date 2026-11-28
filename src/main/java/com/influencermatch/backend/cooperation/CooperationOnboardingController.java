package com.influencermatch.backend.cooperation;

import com.influencermatch.backend.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/cooperation-invitations")
public class CooperationOnboardingController {
  final CooperationOnboarding onboarding;
  public record Token(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String token) {}
  public record Registration(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String token,
      @NotBlank @Email @Size(max=255) String email) {
    public Registration { email = email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT); }
  }
  @PostMapping("/registration")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public ApiResponse<?> registration(@Valid @RequestBody Registration body) {
    return ApiResponse.ok(onboarding.requestRegistration(body.token(), body.email()));
  }
  @PostMapping("/context") @PreAuthorize("hasRole('CREATOR') and hasAuthority('CLAIM_CREATOR_PROFILE')")
  public ApiResponse<?> context(@Valid @RequestBody Token body) { return ApiResponse.ok(onboarding.context(body.token())); }
  @PostMapping("/continue") @PreAuthorize("hasRole('CREATOR') and hasAuthority('RESPOND_OWN_COOPERATION')")
  public ApiResponse<?> proceed(@Valid @RequestBody Token body) { return ApiResponse.ok(onboarding.proceed(body.token())); }
}
