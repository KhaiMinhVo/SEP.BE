package com.influencermatch.backend.notification.enums;

/**
 * Luồng 15.1: Classification of notification occurrence lifecycles.
 * Distinguishes single-occurrence lifecycle events from recurring operational event instances.
 */
public enum OccurrencePolicy {
  /**
   * Nhóm A: Lifecycle event that should only occur once per business entity.
   * (e.g. COLLABORATION_PROPOSAL, PAYMENT_SUCCESS, CLAIM_APPROVED, COLLABORATION_COMPLETED).
   */
  SINGLE_PER_ENTITY,

  /**
   * Nhóm B: Operational event that can legitimately occur multiple times across the entity lifecycle.
   * Dedup is scoped to the specific event/sub-entity instance ID (e.g. EVIDENCE_ASSET ID, submission ID, revision ID).
   * (e.g. EVIDENCE_SUBMITTED, CONTENT_DRAFT_SUBMITTED, CONTENT_CHANGE_REQUESTED).
   */
  MULTI_EVENT_INSTANCE
}
