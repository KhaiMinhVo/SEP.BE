package com.influencermatch.backend.auth.service;
public interface VerificationEmailSender {
  void requireConfigured();
  void send(String email,String otp);
  void sendCreatorInvitation(String email,String link);
  void sendCreatorClaimInvitation(String email,String link);
  void sendPasswordSetup(String email,String otp);
}
