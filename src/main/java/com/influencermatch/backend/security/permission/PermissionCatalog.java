package com.influencermatch.backend.security.permission;

import java.util.*;

/** Explicit inventory of implemented capability endpoints, not inferred from enum declarations. */
public final class PermissionCatalog {
  private PermissionCatalog() {}

  public record Entry(String name, String module) {}

  private static final Map<Permission, Entry> ENTRIES = new EnumMap<>(Permission.class);

  static {
    add(
        "Campaign History",
        Permission.VIEW_OWN_HISTORY_AND_INSIGHTS,
        "Read own Campaign History and preview Duplicate Brief");
    add(
        "Recommendation",
        Permission.RUN_RECOMMENDATION,
        "Run recommendation on authorized candidate sources");
    add("Recommendation", Permission.VIEW_OWN_RECOMMENDATION, "Read own recommendation results");
    add(
        "Creator Portal",
        Permission.MANAGE_OWN_CREATOR_PROFILE,
        "Manage own declared Creator profile");
    add(
        "Creator Contacts",
        Permission.VIEW_CREATOR_CONTACT,
        "Read permitted Creator contact fields");
    add(
        "Cooperation Portal",
        Permission.RESPOND_OWN_COOPERATION,
        "Respond to own cooperation proposals");
    add("Notifications", Permission.VIEW_OWN_NOTIFICATION, "Read own cooperation notifications");
    add("Notifications", Permission.MANAGE_OWN_NOTIFICATION, "Mark own notifications as read");
    add(
        "Identity Catalog",
        Permission.VIEW_CREATOR_MASTER_CATALOG,
        "Read Master Catalog and unclaimed channels");
    add(
        "Identity History",
        Permission.VIEW_CREATOR_IDENTITY_HISTORY,
        "Read whitelisted identity history");
    add("Creator Registration", Permission.INVITE_CREATOR, "Invite Creator registration");
    add("Authentication", Permission.VIEW_OWN_PROFILE, "View own profile");
    add("Creators", Permission.VIEW_CREATOR_PROFILE, "View creator profile");
    add("Brand Profile", Permission.CREATE_BRAND_PROFILE, "Create brand profile");
    add("Brand Profile", Permission.UPDATE_BRAND_PROFILE, "Update brand profile");
    add("Brand Profile", Permission.VIEW_OWN_BRAND_DATA, "View own brand profile");
    add("Campaigns", Permission.CREATE_CAMPAIGN, "Create campaign");
    add("Campaigns", Permission.VIEW_OWN_CAMPAIGN, "View own campaigns");
    add("Campaigns", Permission.UPDATE_CAMPAIGN, "Update campaign");
    add("Campaigns", Permission.CHANGE_CAMPAIGN_STATUS, "Change campaign status and archive");
    add("Creator Discovery", Permission.SEARCH_CREATOR, "Search creators");
    add("Shortlist", Permission.MANAGE_OWN_SHORTLIST, "Manage and compare shortlist");
    add("Relationships", Permission.MANAGE_OWN_RELATIONSHIP, "Manage creator relationships");
    add("Collaborations", Permission.MANAGE_OWN_COLLABORATION, "Create collaboration");
    add("Collaborations", Permission.RECORD_OWN_OUTCOME, "Record campaign outcome");
    add("Collaborations", Permission.RECORD_OWN_CREATOR_REVIEW, "Review creator");
    add("Creator Ownership", Permission.CLAIM_CREATOR_PROFILE, "Submit ownership claim");
    add("Creator Ownership", Permission.VIEW_OWN_CREATOR_CLAIM, "View own ownership claims");
    add("Creator Ownership", Permission.REVIEW_CREATOR_CLAIM, "Review and revoke ownership claims");
    add("Data Quality", Permission.REVIEW_CREATOR_CLASSIFICATION, "Review data quality and niche");
    add("Billing", Permission.VIEW_PLAN, "View subscription plans");
    add("Billing", Permission.MANAGE_OWN_SUBSCRIPTION, "Manage own subscription and checkout");
    add("Users", Permission.VIEW_USER, "View users");
    add("Users", Permission.CHANGE_USER_STATUS, "Change account status");
    add("Users", Permission.ASSIGN_USER_ROLE, "Assign account role");
    add("Billing", Permission.MANAGE_PLAN, "Manage plans");
    add("Billing", Permission.MANAGE_SUBSCRIPTION, "Manage subscriptions");
    add("Audit", Permission.VIEW_AUDIT_LOG, "View audit logs");
    add("Support", Permission.VIEW_BRAND_SUPPORT_DATA, "View brand support data");
  }

  private static void add(String module, Permission permission, String name) {
    ENTRIES.put(permission, new Entry(name, module));
  }

  public static boolean implemented(Permission p) {
    return ENTRIES.containsKey(p);
  }

  public static Entry entry(Permission p) {
    return ENTRIES.getOrDefault(p, new Entry(p.name(), "Not implemented"));
  }
}
