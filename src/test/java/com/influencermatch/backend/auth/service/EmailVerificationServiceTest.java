package com.influencermatch.backend.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.influencermatch.backend.auth.model.*;
import com.influencermatch.backend.auth.repository.*;
import com.influencermatch.backend.exception.*;
import com.influencermatch.backend.user.model.User;
import com.influencermatch.backend.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {
  @Mock UserRepository users;
  @Mock EmailVerificationOtpRepository codes;
  @Mock EmailVerificationDeliveryRepository deliveries;
  @Mock VerificationEmailSender sender;
  EmailOtpCodes crypto;
  EmailVerificationService service;
  Instant now=Instant.parse("2026-10-22T00:00:00Z");
  User user;
  @BeforeEach void setup() {
    crypto=spy(new EmailOtpCodes("test-email-otp-secret-at-least-32-characters"));
    service=new EmailVerificationService(users,codes,deliveries,sender,crypto,Clock.fixed(now,ZoneOffset.UTC));
    user=User.builder().id(UUID.randomUUID()).email("brand@example.test").emailVerified(false).build();
  }
  EmailVerificationOtp otp() {
    var code=new EmailVerificationOtp();code.setUserId(user.getId());code.setCodeHash(crypto.hash(user.getId(),"000042"));
    code.setExpiresAt(now.plusSeconds(600));code.setLastSentAt(now.minusSeconds(60));return code;
  }
  void pending(EmailVerificationOtp code) {
    when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));
    when(codes.findById(user.getId())).thenReturn(Optional.of(code));
  }
  @Test void initialDeliveryKeepsLeadingZerosAndOnlyStoresKeyedHash() {
    doReturn("000042").when(crypto).generate();service.sendInitial(user);
    var captor=ArgumentCaptor.forClass(EmailVerificationOtp.class);verify(codes).save(captor.capture());
    assertThat(captor.getValue().getCodeHash()).hasSize(64).isNotEqualTo("000042");
    assertThat(captor.getValue().getExpiresAt()).isEqualTo(now.plusSeconds(600));
    verify(sender).send(user.getEmail(),"000042");verify(deliveries).save(any());
  }
  @Test void verifyNormalizesEmailConsumesCodeAndMarksVerified() {
    var code=otp();pending(code);service.verify(" BRAND@EXAMPLE.TEST ","000042");
    assertThat(user.isEmailVerified()).isTrue();assertThat(code.getConsumedAt()).isEqualTo(now);
    verifyNoInteractions(sender,deliveries);
  }
  @ParameterizedTest @ValueSource(ints={0,1,2,3,4})
  void wrongAttemptIsCountedAndFifthLocksCode(int attempts) {
    var code=otp();code.setAttempts(attempts);pending(code);
    assertThatThrownBy(()->service.verify(user.getEmail(),"999999")).isInstanceOfSatisfying(EmailOtpAttemptException.class,
        e->assertThat(e.code()).isEqualTo(attempts==4?ErrorCode.EMAIL_OTP_RATE_LIMITED:ErrorCode.EMAIL_OTP_INVALID_OR_EXPIRED));
    assertThat(code.getAttempts()).isEqualTo(attempts+1);assertThat(user.isEmailVerified()).isFalse();
  }
  @ParameterizedTest @ValueSource(strings={"expired","consumed","exhausted"})
  void unusableCodesNeverVerify(String state) {
    var code=otp();if(state.equals("expired"))code.setExpiresAt(now);
    if(state.equals("consumed"))code.setConsumedAt(now);if(state.equals("exhausted"))code.setAttempts(5);pending(code);
    assertThatThrownBy(()->service.verify(user.getEmail(),"000042")).isInstanceOf(EmailOtpAttemptException.class);
    assertThat(user.isEmailVerified()).isFalse();
  }
  @Test void alreadyVerifiedAndUnknownEmailsCannotBeVerified() {
    when(users.findLockedByEmail(user.getEmail())).thenReturn(Optional.of(user));user.setEmailVerified(true);
    assertThatThrownBy(()->service.verify(user.getEmail(),"000042")).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_ALREADY_VERIFIED));
    when(users.findLockedByEmail("missing@example.test")).thenReturn(Optional.empty());
    assertThatThrownBy(()->service.verify("missing@example.test","000042")).isInstanceOf(EmailOtpAttemptException.class);
    verifyNoInteractions(codes);
  }
  @ParameterizedTest @ValueSource(booleans={true,false})
  void resendDoesNothingForVerifiedOrUnknownEmail(boolean exists) {
    user.setEmailVerified(true);when(users.findLockedByEmail(user.getEmail())).thenReturn(exists?Optional.of(user):Optional.empty());
    service.resend(user.getEmail());verify(sender).requireConfigured();verifyNoMoreInteractions(sender);verifyNoInteractions(codes,deliveries);
  }
  @Test void resendAfterCooldownResetsAttemptsAndInvalidatesOldCode() {
    var code=otp();code.setAttempts(5);pending(code);doReturn("000043").when(crypto).generate();
    service.resend(user.getEmail());assertThat(code.getAttempts()).isZero();assertThat(code.getConsumedAt()).isNull();
    assertThat(crypto.matches(user.getId(),"000042",code.getCodeHash())).isFalse();verify(sender).send(user.getEmail(),"000043");
  }
  @ParameterizedTest @ValueSource(strings={"cooldown","hourly"})
  void resendLimitsNeverReplaceCodeOrSend(String limit) {
    var code=otp();if(limit.equals("cooldown"))code.setLastSentAt(now.minusSeconds(59));pending(code);
    if(limit.equals("hourly"))when(deliveries.countByUserIdAndSentAtAfter(user.getId(),now.minusSeconds(3600))).thenReturn(5L);
    assertThatThrownBy(()->service.resend(user.getEmail())).isInstanceOfSatisfying(BusinessException.class,
        e->assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_OTP_RATE_LIMITED));
    verify(codes,never()).save(any());verify(sender,never()).send(anyString(),anyString());
  }
  @Test void resendRegeneratesIfRandomCodeMatchesPreviousCode() {
    var code=otp();pending(code);doReturn("000042","000043").when(crypto).generate();
    service.resend(user.getEmail());verify(sender).send(user.getEmail(),"000043");
    assertThat(crypto.matches(user.getId(),"000042",code.getCodeHash())).isFalse();
  }
  @Test void unavailableSenderFailsBeforeAnyLookup() {
    doThrow(new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE,"Unavailable")).when(sender).requireConfigured();
    assertThatThrownBy(()->service.resend(user.getEmail())).isInstanceOf(BusinessException.class);
    verifyNoInteractions(users,codes,deliveries);
  }
  @Test void keyedHashIsBoundToAccountAndSecretAndGeneratorHasSixDigits() {
    assertThat(crypto.generate()).matches("[0-9]{6}");
    assertThat(crypto.hash(user.getId(),"000042")).isNotEqualTo(crypto.hash(user.getId(),"password-setup","000042"));
    assertThat(crypto.hash(user.getId(),"000042")).isNotEqualTo(crypto.hash(UUID.randomUUID(),"000042"))
        .isNotEqualTo(new EmailOtpCodes("different-otp-secret-at-least-32-characters").hash(user.getId(),"000042"));
  }
}
