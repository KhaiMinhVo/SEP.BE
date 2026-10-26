package com.influencermatch.backend.exception;
/** Only raised before any business mutation; allows rejection audits to commit. */
public class CreatorOwnershipConflictException extends ConflictException {
  public CreatorOwnershipConflictException(ErrorCode code, String message) { super(code,message); }
}
