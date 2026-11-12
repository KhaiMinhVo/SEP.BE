package com.influencermatch.backend.exception;

public class CampaignEmbeddingPendingException extends BusinessException {
  private final String status;

  public CampaignEmbeddingPendingException(String status) {
    super(ErrorCode.CAMPAIGN_EMBEDDING_NOT_READY, "Campaign embedding is " + status);
    this.status = status;
  }

  public String status() {
    return status;
  }
}
