package com.influencermatch.backend.creator.portal;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.*;
import java.util.*;

public final class PortalDtos {
  private PortalDtos() {}

  public record BookingAddress(@jakarta.validation.constraints.Email String value) {}

  public enum Visibility {
    PRIVATE,
    BRANDS,
    CONFIRMED_PARTNERS
  }

  public enum AvailabilityStatus {
    OPEN,
    LIMITED,
    CLOSED
  }

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Contact(String value, Visibility visibility) {}

  public record Contacts(Contact bookingEmail, Contact zalo, Contact managerContact) {}

  public record Sharing(
      boolean introduction,
      boolean serviceAreas,
      boolean acceptedNiches,
      boolean portfolio,
      boolean availability) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Portfolio(UUID id, String title, String url, String description) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Availability(AvailabilityStatus status, LocalDate availableFrom, String note) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Data(
      String introduction,
      List<String> serviceAreas,
      List<String> acceptedNiches,
      List<Portfolio> portfolio,
      Availability availability,
      Sharing sharing,
      Contacts contacts) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Profile(
      UUID masterId, boolean profileExists, long profileRevision, String source, Data profile) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record DeclaredProfile(
      String source,
      String introduction,
      List<String> serviceAreas,
      List<String> acceptedNiches,
      List<Portfolio> portfolio,
      Availability availability) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Verification(
      String status,
      String method,
      LocalDateTime verifiedAt,
      String evidenceSource,
      String environment) {
    public boolean verified() {
      return "VERIFIED".equals(status);
    }
  }

  public record Projection(
      UUID masterId,
      Verification verification,
      DeclaredProfile declaredProfile,
      String tokenStatus) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record SharedContact(String value, String availability, String source, boolean verified) {}

  public record SharedContacts(
      UUID creatorId,
      SharedContact bookingEmail,
      SharedContact zalo,
      SharedContact managerContact) {}

  public record ConnectionOption(
      String platform,
      String oauthFlow,
      String environment,
      boolean supported,
      boolean available,
      String unavailableReason,
      List<String> requirements) {}
}
