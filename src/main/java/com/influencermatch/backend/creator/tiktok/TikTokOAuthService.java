package com.influencermatch.backend.creator.tiktok;

import com.influencermatch.backend.creator.client.provider.TikTokSandboxProofProvider;
import com.influencermatch.backend.exception.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class TikTokOAuthService {
  private final TikTokSessionStore store;
  private final TikTokSandboxProofProvider provider;
  private final TikTokOwnershipService ownership;
  private final TikTokIdentityResolver resolver;

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public TikTokDtos.Callback callback(
      String state, String code, String error, Map<String, String> cookies) {
    var id = store.reserve(state, cookies).unwrap();
    try {
      if (error != null)
        throw new BusinessException(
            ErrorCode.TIKTOK_AUTHORIZATION_REJECTED, "TikTok authorization was denied");
      var proof = provider.verify(code);
      var identity = resolver.resolve(proof);
      return ownership.apply(id, proof, identity).unwrap();
    } catch (RuntimeException ex) {
      store.fail(id);
      throw ex;
    }
  }
}
