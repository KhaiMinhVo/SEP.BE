package com.influencermatch.backend.service;
import com.influencermatch.backend.entity.*; import com.influencermatch.backend.exception.*; import com.influencermatch.backend.repository.*; import lombok.RequiredArgsConstructor; import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets; import java.security.*; import java.time.LocalDateTime; import java.util.*;
@Service @RequiredArgsConstructor public class RefreshTokenService {
    private final RefreshTokenRepository tokens; private final UserRepository users; private final SecureRandom random=new SecureRandom();
    @Value("${application.security.jwt.refresh-token.expiration}") private long expirationMs;
    @Transactional public Issued issue(User user){return create(user);}
    @Transactional public Rotated rotate(String raw){LocalDateTime now=LocalDateTime.now();RefreshToken old=tokens.findByTokenHash(hash(raw)).orElseThrow(this::invalid);if(!old.active(now)){if(old.getRevokedAt()!=null)tokens.revokeAll(old.getUserId(),now);throw invalid();}User user=users.findById(old.getUserId()).filter(User::isEnabled).orElseThrow(this::invalid);old.setRevokedAt(now);Issued next=create(user);old.setReplacedByTokenId(next.entity().getId());return new Rotated(user,next.raw());}
    @Transactional public void revoke(String raw){tokens.findByTokenHash(hash(raw)).ifPresent(t->{if(t.getRevokedAt()==null)t.setRevokedAt(LocalDateTime.now());});}
    @Transactional public void revokeAll(UUID userId){tokens.revokeAll(userId,LocalDateTime.now());}
    private Issued create(User user){byte[] bytes=new byte[48];random.nextBytes(bytes);String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);RefreshToken entity=tokens.save(RefreshToken.builder().userId(user.getId()).tokenHash(hash(raw)).jti(UUID.randomUUID()).createdAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusNanos(expirationMs*1_000_000)).build());return new Issued(raw,entity);}
    private BusinessException invalid(){return new BusinessException(ErrorCode.UNAUTHENTICATED,"Refresh token is invalid or expired");}
    private String hash(String raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public record Issued(String raw,RefreshToken entity){} public record Rotated(User user,String refreshToken){}
}
