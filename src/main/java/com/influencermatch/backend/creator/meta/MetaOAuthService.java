package com.influencermatch.backend.creator.meta;

import com.influencermatch.backend.exception.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class MetaOAuthService {
  private final MetaSessionStore store;
  private final MetaGraphClient graph;
  private final InstagramGraphClient instagram;
  private final MetaOwnershipService ownership;

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public MetaDtos.Callback callback(
      String state, String code, String error, java.util.Map<String, String> cookies) {
    return callback(state, code, error, cookies, OAuthFlow.FACEBOOK_LOGIN);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public MetaDtos.Callback instagramCallback(
      String state, String code, String error, java.util.Map<String, String> cookies) {
    return callback(state, code, error, cookies, OAuthFlow.INSTAGRAM_LOGIN);
  }

  private MetaDtos.Callback callback(
      String state,
      String code,
      String error,
      java.util.Map<String, String> cookies,
      OAuthFlow flow) {
    var exchange = store.reserve(state, cookies, flow);
    try {
      if (error != null || code == null || code.isBlank())
        throw new BusinessException(
            ErrorCode.META_AUTHORIZATION_REJECTED, "Meta authorization was not completed");
      return store.ready(
          exchange.sessionId(),
          flow == OAuthFlow.INSTAGRAM_LOGIN
              ? instagram.exchange(code)
              : graph.exchange(code, exchange.platform()));
    } catch (RuntimeException failure) {
      store.fail(exchange.sessionId());
      if (failure instanceof BusinessException business) throw business;
      throw new BusinessException(
          ErrorCode.META_PROVIDER_ERROR, "Meta authorization could not be completed");
    }
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public MetaDtos.Connected confirm(UUID id, String accountId) {
    var selected = store.selection(id, accountId);
    MetaDtos.Proof fresh;
    try {
      fresh =
          selected.oauthFlow() == OAuthFlow.INSTAGRAM_LOGIN
              ? instagram.verify(selected.proof())
              : graph.verify(selected.proof().token(), selected.platform());
    } catch (BusinessException failure) {
      if (failure.code() == ErrorCode.META_AUTHORIZATION_REJECTED) store.failReady(id);
      throw failure;
    }
    if (!fresh.subject().equals(selected.proof().subject())) {
      store.failReady(id);
      throw new ForbiddenException("Meta authorization changed");
    }
    return ownership.apply(id, accountId, fresh);
  }
}
